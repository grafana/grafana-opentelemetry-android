#!/usr/bin/env python3
"""Local emulator check. Clears only com.grafana.sessiondraftprobe's data."""

import argparse
import gzip
import hashlib
import json
import re
import shlex
import subprocess
import threading
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from opentelemetry.proto.collector.logs.v1.logs_service_pb2 import ExportLogsServiceRequest
from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest

PACKAGE = "com.grafana.sessiondraftprobe"
records, results, errors = [], [], []
lock = threading.Lock()
available = threading.Event()
available.set()
rejected = threading.Event()


def value(item):
    kind = item.WhichOneof("value")
    if kind == "array_value":
        return [value(v) for v in item.array_value.values]
    if kind == "kvlist_value":
        return attrs(item.kvlist_value.values)
    if kind == "bytes_value":
        return item.bytes_value.hex()
    return getattr(item, kind) if kind else None


def attrs(items):
    return {item.key: value(item.value) for item in items}


def sampling_names(phase):
    cases = ["root"] + [f"remote-{remote}-sampled-{sampled}"
                        for remote in ("false", "true") for sampled in ("false", "true")]
    return [f"probe.sampling.{phase}.{case}" for case in cases]


def verify_sampling(batch, phase, session):
    for name in sampling_names(phase):
        spans = [r for r in batch if r["kind"] == "span" and r["name"] == name]
        logs = [r for r in batch if r["kind"] == "log" and r["name"] == name]
        assert len(spans) == 1 and len(logs) == 1, (name, spans, logs)
        span, log = spans[0], logs[0]
        assert span["attrs"]["session.id"] == log["attrs"]["session.id"] == session
        assert span["flags"] & 1 and log["flags"] & 1, name
        assert span["traceId"] == log["traceId"] and span["spanId"] == log["spanId"], name
        assert span["traceId"] and span["spanId"], name
        if name.endswith(".root"):
            assert not span["parentSpanId"], name
        else:
            assert span["traceId"] == "1" * 32 and span["parentSpanId"] == "2" * 16, name


def verify_identity(first, current):
    for key in ("pid", "process", "launch"):
        assert current[key] == first[key], (key, first[key], current[key])
    assert not current.get("storageFailure"), current


def verify_elapsed(device_ms, host_seconds, minimum, maximum=None):
    assert device_ms >= minimum * 1000 and host_seconds >= minimum, (device_ms, host_seconds)
    assert abs(device_ms / 1000 - host_seconds) < 10, (device_ms, host_seconds)
    if maximum is not None:
        assert device_ms < maximum * 1000 and host_seconds < maximum, (device_ms, host_seconds)


def verify_checkpoint(batch, current, sessions):
    checkpoint = current["checkpoint"]
    assert 0 <= checkpoint["afterMs"] - checkpoint["beforeMs"] < 1000, checkpoint
    assert checkpoint["session"] == sessions[-1], checkpoint
    name = "probe.realtime." + checkpoint["token"]
    for kind in ("log", "span"):
        matches = [r for r in batch if r["kind"] == kind and r["name"] == name]
        assert len(matches) == 1, (name, matches)
        assert matches[0]["attrs"]["session.id"] == checkpoint["session"], matches
    lifecycle = [r for r in batch if r["kind"] == "log" and r["name"] in ("session.start", "session.end")]
    expected = []
    for index, session in enumerate(sessions):
        if index:
            expected.append(("session.end", sessions[index - 1], None))
        expected.append(("session.start", session, sessions[index - 1] if index else None))
    assert [(r["name"], r["attrs"]["session.id"], r["attrs"].get("session.previous_id"))
            for r in lifecycle] == expected, lifecycle


class Collector(BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            self.connection.settimeout(10)
            if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                body = bytearray()
                while True:
                    size = int(self.rfile.readline().split(b";", 1)[0], 16)
                    if size == 0:
                        while self.rfile.readline() not in (b"\r\n", b""):
                            pass
                        break
                    assert 0 < size <= 8 * 1024 * 1024 - len(body)
                    body.extend(self.rfile.read(size))
                    assert self.rfile.read(2) == b"\r\n"
                body = bytes(body)
            else:
                size = int(self.headers["Content-Length"])
                assert 0 <= size <= 8 * 1024 * 1024
                body = self.rfile.read(size)
            if not available.is_set():
                rejected.set()
                self.send_response(503)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.headers.get("Content-Encoding") == "gzip":
                body = gzip.decompress(body)
            batch = []
            if self.path == "/v1/logs":
                request = ExportLogsServiceRequest.FromString(body)
                groups = request.resource_logs
                for group in groups:
                    for scope in group.scope_logs:
                        for entry in scope.log_records:
                            batch.append(dict(kind="log", name=entry.event_name, scope=scope.scope.name,
                                              time=entry.time_unix_nano, attrs=attrs(entry.attributes),
                                              flags=entry.flags, traceId=entry.trace_id.hex(), spanId=entry.span_id.hex(),
                                              resource=attrs(group.resource.attributes)))
            elif self.path == "/v1/traces":
                request = ExportTraceServiceRequest.FromString(body)
                for group in request.resource_spans:
                    for scope in group.scope_spans:
                        for entry in scope.spans:
                            batch.append(dict(kind="span", name=entry.name, scope=scope.scope.name,
                                              time=entry.start_time_unix_nano, attrs=attrs(entry.attributes),
                                              flags=entry.flags, traceId=entry.trace_id.hex(), spanId=entry.span_id.hex(),
                                              parentSpanId=entry.parent_span_id.hex(),
                                              resource=attrs(group.resource.attributes)))
            else:
                raise AssertionError(f"Unexpected signal path: {self.path}")
            with lock:
                records.extend(batch)
            self.send_response(200)
            self.send_header("Content-Type", "application/x-protobuf")
            self.send_header("Content-Length", "0")
            self.end_headers()
        except Exception as error:
            errors.append(repr(error))
            self.send_error(500)

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--apk", required=True, help="Exact APK installed on the emulator")
    parser.add_argument("--root-files", action="store_true", help="Use a rooted disposable emulator for a non-debuggable APK")
    parser.add_argument("--crash-only", action="store_true")
    parser.add_argument("--real-time", action="store_true", help="Run actual 14/16-minute background waits and a four-hour foreground lifetime")
    parser.add_argument("--crash-mode", choices=("abort", "signal"), default="abort")
    parser.add_argument("--without-native-capture", action="store_true", help="Expect no native-crash event from the comparison APK")
    args = parser.parse_args()
    if not __debug__:
        parser.error("Assertions must be enabled; do not use python -O")
    if args.real_time and (args.crash_only or args.without_native_capture):
        parser.error("--real-time cannot be combined with crash comparison modes")
    evidence = Path(args.evidence)
    evidence.mkdir(parents=True, exist_ok=False)

    def adb(*command):
        return subprocess.run([args.adb, "-s", args.serial, *command], check=True,
                              timeout=45, capture_output=True).stdout.decode().strip()

    def shell(command):
        if args.root_files:
            return adb("shell", "su", app_uid, "sh", "-c", shlex.quote(
                "cd /data/data/" + PACKAGE + " && " + command))
        return adb("shell", "run-as", PACKAGE, "sh", "-c", shlex.quote(command))

    def stop():
        adb("shell", "am", "force-stop", PACKAGE)

    def wait(predicate, seconds=60):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            assert not errors, errors
            result = predicate()
            if result:
                return result
            time.sleep(0.2)
        raise AssertionError("Timed out waiting for " + predicate.__name__)

    def snapshot():
        with lock:
            return list(records)

    def events(report, name, kind="log"):
        return [r for r in snapshot() if r["name"] == name and r["kind"] == kind
                and r["resource"].get("probe.launch") == report["launch"]]

    def report(role):
        try:
            return json.loads(shell(f"cat files/report-{role}.json"))
        except (subprocess.CalledProcessError, ValueError):
            return None

    def check_sampling(current, phase, session):
        for name in sampling_names(phase):
            wait(lambda: events(current, name, "span"))
            wait(lambda: events(current, name))
        verify_sampling([r for r in snapshot() if r["resource"].get("probe.launch") == current["launch"]], phase, session)

    def launch(role="main", reset=False, crash=False, telemetry=True):
        token = str(time.monotonic_ns())
        activity = {"main": "ProbeActivity", "worker": "WorkerActivity", "contender": "ContenderActivity"}[role]
        cmd = ["shell", "am", "start", "-W", "-f", "0x10008000", "-n", PACKAGE + "/." + activity,
               "--es", "actionToken", token]
        if reset:
            cmd += ["--ez", "reset", "true"]
        if crash:
            cmd += ["--ez", "nativeCrash", "true", "--es", "crashMode", args.crash_mode]
        output = adb(*cmd)
        assert "Error" not in output, output

        def ready():
            current = report(role)
            if current and current.get("actionToken") == token:
                assert not current.get("initializationError"), current
                return current

        current = wait(ready)
        if telemetry and role != "contender":
            wait(lambda: events(current, "probe.early-startup", "span"))
            wait(lambda: events(current, "probe.early-startup"))
            wait(lambda: events(current, "session.start"))
            for r in events(current, "probe.early-startup") + events(current, "probe.early-startup", "span"):
                assert r["resource"]["process.title"] == current["process"]
            check_sampling(current, "startup", events(current, "probe.early-startup")[0]["attrs"]["session.id"])
            if reset:
                check_sampling(current, "after-reset", current["session"])
            assert current["duplicateFailure"] == "storage-unavailable"
        return current

    def record_path(role="main"):
        name = PACKAGE + (":" + role if role != "main" else "")
        return "no_backup/grafana-sessions/" + hashlib.sha256(name.encode()).hexdigest() + ".json"

    def stored(role="main"):
        return json.loads(shell("cat " + record_path(role)))

    def wait_stored(current, role="main"):
        def committed():
            try:
                return stored(role)["id"] == current["session"]
            except (subprocess.CalledProcessError, ValueError, KeyError):
                return False
        wait(committed)

    def previous(current):
        matches = [r for r in events(current, "session.start") if r["attrs"]["session.id"] == current["session"]]
        assert len(matches) == 1, matches
        return matches[0]["attrs"].get("session.previous_id")

    def write(path, text):
        shell("printf %s " + shlex.quote(text) + " > " + shlex.quote(path))

    def passed(name, **details):
        results.append(dict(test=name, status="passed", **details))
        (evidence / "results.json").write_text(json.dumps(results, indent=2))
        print("PASS " + name, flush=True)

    def real_time_check():
        shell("mkdir -p files")
        write("files/realtime", "yes")
        first = launch()
        sessions = [first["session"]]
        checkpoints = []

        def save_progress():
            (evidence / "realtime-checkpoints.json").write_text(json.dumps(checkpoints, indent=2))
            (evidence / "otlp-records.json").write_text(json.dumps(snapshot(), indent=2))

        def current_report():
            current = wait(lambda: report("main"))
            verify_identity(first, current)
            assert adb("shell", "pidof", PACKAGE) == str(first["pid"]), "Probe process changed"
            return current

        def checkpoint(token, rotate=False):
            host_before = time.monotonic()
            adb("shell", "am", "broadcast", "-f", "0x10000000", "-n", PACKAGE + "/.CheckpointReceiver",
                "--es", "checkpoint", token)

            def ready():
                current = report("main")
                return current if current and current.get("checkpoint", {}).get("token") == token else None

            current = wait(ready)
            current["hostBefore"] = host_before
            current["hostAfter"] = time.monotonic()
            checkpoints.append(current)
            save_progress()
            verify_identity(first, current)
            if rotate:
                assert current["checkpoint"]["session"] not in sessions, current
                sessions.append(current["checkpoint"]["session"])
            wait(lambda: events(current, "probe.realtime." + token))
            wait(lambda: events(current, "probe.realtime." + token, "span"))
            batch = [r for r in snapshot() if r["resource"].get("probe.launch") == first["launch"]]
            verify_checkpoint(batch, current, sessions)
            save_progress()
            return current

        def hold(until, expected_lifecycle, label):
            print("WAIT " + label, flush=True)
            next_progress = time.monotonic() + 300
            while time.monotonic() < until:
                assert not errors, errors
                current = current_report()
                assert current["lifecycle"] == expected_lifecycle, "Unexpected activity transition during idle wait"
                if time.monotonic() >= next_progress:
                    print(f"WAIT {label}: {max(0, until - time.monotonic()):.0f}s remaining", flush=True)
                    save_progress()
                    next_progress = time.monotonic() + 300
                time.sleep(min(30, max(0, until - time.monotonic())))
            assert current_report()["lifecycle"] == expected_lifecycle

        baseline = checkpoint("initial")
        assert [entry["state"] for entry in baseline["lifecycle"]] == ["foreground"]
        for minutes, rotate in ((14, False), (16, True)):
            before = current_report()
            host_start = time.monotonic()
            adb("shell", "input", "keyevent", "KEYCODE_HOME")

            def backgrounded():
                current = report("main")
                if current and len(current["lifecycle"]) == len(before["lifecycle"]) + 1:
                    assert current["lifecycle"][-1]["state"] == "background"
                    return current

            background = wait(backgrounded)
            verify_identity(first, background)
            hold(time.monotonic() + minutes * 60, background["lifecycle"], f"{minutes} minutes in background")
            adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            adb("shell", "wm", "dismiss-keyguard")
            adb("shell", "am", "start", "-W", "-f", "0x20000000", "-n", PACKAGE + "/.ProbeActivity")

            def foregrounded():
                current = report("main")
                if current and len(current["lifecycle"]) == len(background["lifecycle"]) + 1:
                    assert current["lifecycle"][-1]["state"] == "foreground"
                    return current

            foreground = wait(foregrounded)
            host_elapsed = time.monotonic() - host_start
            device_elapsed = foreground["lifecycle"][-1]["elapsedMs"] - background["lifecycle"][-1]["elapsedMs"]
            verify_elapsed(device_elapsed, host_elapsed, minutes * 60, 900 if not rotate else None)
            assert foreground["lifecycle"][:-1] == background["lifecycle"]
            time.sleep(2)
            baseline = checkpoint(f"background-{minutes}m", rotate=rotate)
            passed(f"real-time background return after {minutes} minutes", deviceElapsedMs=device_elapsed,
                   hostElapsedSeconds=host_elapsed, checkpoint=baseline)

        # The new session is now foregrounded. Do not touch its provider between checkpoints.
        foreground_history = baseline["lifecycle"]
        hold(baseline["hostAfter"] + 16 * 60, foreground_history, "16 minutes idle in foreground")
        idle = checkpoint("foreground-idle-16m")
        verify_elapsed(idle["checkpoint"]["beforeMs"] - baseline["checkpoint"]["afterMs"],
                       idle["hostBefore"] - baseline["hostAfter"], 16 * 60)
        passed("foreground idle does not expire the session", checkpoint=idle)

        hold(baseline["hostAfter"] + 239 * 60, foreground_history, "before four-hour lifetime")
        before_expiry = checkpoint("lifetime-3h59m")
        verify_elapsed(before_expiry["checkpoint"]["beforeMs"] - baseline["checkpoint"]["afterMs"],
                       before_expiry["hostBefore"] - baseline["hostAfter"], 239 * 60, 240 * 60)
        passed("session retained before four-hour lifetime", checkpoint=before_expiry)

        hold(baseline["hostAfter"] + 241 * 60, foreground_history, "after four-hour lifetime")
        expired = checkpoint("lifetime-4h1m", rotate=True)
        verify_elapsed(expired["checkpoint"]["beforeMs"] - baseline["checkpoint"]["afterMs"],
                       expired["hostBefore"] - baseline["hostAfter"], 241 * 60)
        for current in (idle, before_expiry, expired):
            assert current["lifecycle"] == foreground_history, "Foreground lifetime was interrupted"
        passed("session replaced and linked after four-hour lifetime", checkpoint=expired)
        save_progress()

    def crash_check():
        crashed = launch(reset=True, crash=True)
        assert crashed["crashMode"] == args.crash_mode

        def terminated():
            return adb("shell", "sh", "-c", shlex.quote(
                f"if [ -d /proc/{crashed['pid']} ]; then echo alive; else echo gone; fi")) == "gone"

        try:
            wait(terminated, seconds=45)
        finally:
            (evidence / "crash-process-status.txt").write_text(adb(
                "shell", "sh", "-c", shlex.quote(
                    f"cat /proc/{crashed['pid']}/status /proc/{crashed['pid']}/wchan 2>&1; true")))
        recovery = launch()
        assert previous(recovery) == crashed["session"]

        def recovered():
            return [r for r in snapshot() if r["scope"] == "io.opentelemetry.native-crash"
                    and r["name"] in ("app.crash", "device.crash")]

        if args.without_native_capture:
            time.sleep(5)
            assert not recovered(), "Comparison APK unexpectedly captured a native crash"
            passed(args.crash_mode + " terminates without native capture", crashed=crashed, recovery=recovery)
            return
        crash_records = wait(recovered)
        assert len(crash_records) == 1, crash_records
        assert crash_records[0]["attrs"]["session.id"] == crashed["session"]
        assert recovery["session"] != crashed["session"]
        stop()
        launch()
        time.sleep(5)
        assert len(recovered()) == 1
        passed(args.crash_mode + " after reset replays once under crashed session", crashed=crashed, recovery=recovery)

    assert args.serial.startswith("emulator-"), "Use an emulator, not a personal device"
    assert adb("shell", "getprop", "sys.boot_completed") == "1"
    assert not args.without_native_capture or args.crash_only, "Comparison is crash-only"
    if args.root_files:
        assert adb("shell", "getprop", "ro.debuggable") == "1"
        assert adb("shell", "id", "-u") == "0", "Run adb root on this disposable emulator first"
        uid_match = re.search(r"(?:userId|appId)=(\d+)", adb("shell", "dumpsys", "package", PACKAGE))
        assert uid_match, "Cannot determine the installed app UID"
        app_uid = uid_match.group(1)
    server = ThreadingHTTPServer(("127.0.0.1", 14318), Collector)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        environment = {"serial": args.serial, "api": adb("shell", "getprop", "ro.build.version.sdk"),
                       "abi": adb("shell", "getprop", "ro.product.cpu.abi"),
                       "fingerprint": adb("shell", "getprop", "ro.build.fingerprint"),
                       "rootFiles": args.root_files, "crashOnly": args.crash_only,
                       "realTime": args.real_time,
                       "crashMode": args.crash_mode,
                       "withoutNativeCapture": args.without_native_capture,
                       "package": adb("shell", "dumpsys", "package", PACKAGE)}
        (evidence / "environment.json").write_text(json.dumps(environment, indent=2))
        draft = Path(__file__).resolve().parents[1]
        inputs = {}
        for root in (draft, draft.parent / "android/src/main"):
            for source in sorted(root.rglob("*")):
                if source.is_file() and not {"build", ".gradle", "__pycache__"}.intersection(source.relative_to(root).parts):
                    if source.suffix in (".kt", ".java", ".cpp", ".kts", ".py", ".xml", ".patch", ".lockfile", ".properties") or source.name == "CMakeLists.txt":
                        inputs[str(source.relative_to(draft.parent))] = hashlib.sha256(source.read_bytes()).hexdigest()
        apk = Path(args.apk).resolve()
        apk_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
        installed_path = adb("shell", "pm", "path", PACKAGE).splitlines()
        assert len(installed_path) == 1 and installed_path[0].startswith("package:"), installed_path
        installed_apk = evidence / "installed.apk"
        adb("pull", installed_path[0].removeprefix("package:"), str(installed_apk))
        assert hashlib.sha256(installed_apk.read_bytes()).hexdigest() == apk_hash, "Installed APK differs from --apk"
        with zipfile.ZipFile(apk) as archive:
            native_capture = any(name.endswith("/libotel_android_native_crash.so") for name in archive.namelist())
            assert any(name.endswith("/libsession_probe_crash.so") for name in archive.namelist()), "Missing probe crash library"
        assert native_capture != args.without_native_capture, "APK native capture does not match the requested check"
        inputs["installed.apk"] = apk_hash
        (evidence / "inputs-sha256.json").write_text(json.dumps(inputs, indent=2))
        stop()
        assert adb("shell", "pm", "clear", PACKAGE) == "Success"
        if args.real_time:
            real_time_check()
            return
        if args.crash_only:
            crash_check()
            return
        first = launch()
        assert previous(first) is None
        wait_stored(first)
        stop()
        second = launch()
        assert second["pid"] != first["pid"] and second["session"] != first["session"]
        assert previous(second) == first["session"]
        assert len(events(second, "session.start")) == 1 and not events(second, "session.end")
        wait_stored(second)
        passed("fresh launch and linked process restart", first=first, second=second)

        reset = launch(reset=True)
        wait(lambda: events(reset, "probe.after-reset"))
        wait(lambda: events(reset, "probe.over-reset", "span"))
        assert reset["resetAccepted"] is True and previous(reset) == second["session"]
        assert events(reset, "probe.over-reset", "span")[0]["attrs"]["session.id"] == second["session"]
        assert events(reset, "probe.before-reset")[0]["attrs"]["session.id"] == second["session"]
        assert events(reset, "probe.after-reset")[0]["attrs"]["session.id"] == reset["session"]
        lifecycle = [r for r in snapshot() if r["resource"].get("probe.launch") == reset["launch"]
                     and r["name"] in ("session.start", "session.end")]
        assert [(r["name"], r["attrs"]["session.id"]) for r in lifecycle] == [
            ("session.start", second["session"]), ("session.end", second["session"]), ("session.start", reset["session"])]
        wait_stored(reset)
        passed("reset order and in-flight span attribution", reset=reset)

        contender = launch("contender", telemetry=False)
        assert contender["storageFailure"] == "storage-unavailable" and contender["previous"] == ""
        assert stored()["id"] == reset["session"]
        worker = launch("worker")
        assert previous(worker) is None and worker["session"] != reset["session"]
        wait_stored(worker, "worker")
        stop()
        restarted = launch()
        worker2 = launch("worker")
        assert previous(restarted) == reset["session"] and previous(worker2) == worker["session"]
        passed("Android process chains and rejected competing owner", main=restarted, worker=worker2, contender=contender)

        stop()
        write(record_path(), "not json")
        corrupt = launch()
        assert previous(corrupt) is None
        wait_stored(corrupt)
        assert corrupt["storageFailure"] == "corrupt-record"
        stop()
        future = '{"version":2,"reserved":true}'
        write(record_path(), future)
        unknown = launch()
        assert previous(unknown) is None and shell("cat " + record_path()) == future
        assert unknown["storageFailure"] == "unsupported-version"
        passed("corrupt record recovery and future format preservation")

        stop()
        shell("rm " + record_path())
        writable = launch()
        wait_stored(writable)
        stop()
        before = shell("cat " + record_path())
        shell("chmod 400 " + record_path())
        shell("chmod 500 no_backup/grafana-sessions")
        try:
            failed = launch()
            def write_failed():
                current = report("main")
                return current if current and current.get("storageFailure") == "write-failed" else None
            failed = wait(write_failed)
            assert previous(failed) == writable["session"]
            failed_reset = launch(reset=True)
            wait(lambda: events(failed_reset, "probe.after-reset"))
            assert failed_reset["resetAccepted"] and previous(failed_reset) == failed["session"]
            assert shell("cat " + record_path()) == before
            passed("write failure leaves reset and telemetry working", launch=failed, reset=failed_reset)
        finally:
            stop()
            shell("chmod 700 no_backup/grafana-sessions")
            shell("chmod 600 " + record_path())
        durable = launch()
        assert previous(durable) == writable["session"]
        passed("restart after failed writes links last durable session", restart=durable)

        stop()
        write("files/buffered", "yes")
        available.clear()
        rejected.clear()
        offline = launch(telemetry=False)
        offline_reset = launch(reset=True, telemetry=False)
        offline_worker = launch("worker", telemetry=False)
        assert offline_reset["resetAccepted"]
        assert rejected.wait(45), "No attempted export reached the unavailable receiver"
        buffers = shell("find cache/grafana-signals -type f")
        for process in (PACKAGE, PACKAGE + ":worker"):
            assert hashlib.sha256(process.encode()).hexdigest() in buffers, "Missing process buffer"
        # Leave time for both logger and tracer batches to reach disk, not just memory.
        time.sleep(12)
        stop()
        available.set()
        online = launch()
        wait(lambda: events(offline_reset, "probe.after-reset"), seconds=90)
        wait(lambda: events(offline_reset, "probe.over-reset", "span"), seconds=90)
        assert previous(online) == offline_reset["session"]
        assert events(offline_reset, "probe.after-reset")[0]["attrs"]["session.id"] == offline_reset["session"]
        assert events(offline_reset, "probe.over-reset", "span")[0]["attrs"]["session.id"] == offline["session"]
        assert online["session"] not in (offline["session"], offline_reset["session"])
        assert not events(offline_worker, "probe.early-startup"), "Main replayed the worker buffer"
        online_worker = launch("worker")
        wait(lambda: events(offline_worker, "probe.early-startup"), seconds=90)
        assert previous(online_worker) == offline_worker["session"]
        assert events(offline_worker, "probe.early-startup")[0]["attrs"]["session.id"] == offline_worker["session"]
        check_sampling(offline, "startup", offline["session"])
        check_sampling(offline_reset, "after-reset", offline_reset["session"])
        check_sampling(offline_worker, "startup", offline_worker["session"])
        passed("buffered logs and spans retain attribution across process death", offline=offline,
               reset=offline_reset, online=online, worker=offline_worker, workerRestart=online_worker, bufferFiles=buffers)

        stop()
        shell("rm files/buffered")
        crash_check()
    except BaseException as error:
        results.append(dict(test="run interrupted", status="failed", error=repr(error)))
        raise
    finally:
        available.set()
        try:
            stop()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
            (evidence / "results.json").write_text(json.dumps(results, indent=2))
            (evidence / "otlp-records.json").write_text(json.dumps(snapshot(), indent=2))
            (evidence / "collector-errors.json").write_text(json.dumps(errors))
            (evidence / "logcat.txt").write_text(adb("logcat", "-d", "-v", "threadtime"))


if __name__ == "__main__":
    main()
