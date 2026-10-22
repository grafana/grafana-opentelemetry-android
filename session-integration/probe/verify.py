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
                                              resource=attrs(group.resource.attributes)))
            elif self.path == "/v1/traces":
                request = ExportTraceServiceRequest.FromString(body)
                for group in request.resource_spans:
                    for scope in group.scope_spans:
                        for entry in scope.spans:
                            batch.append(dict(kind="span", name=entry.name, scope=scope.scope.name,
                                              time=entry.start_time_unix_nano, attrs=attrs(entry.attributes),
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
    parser.add_argument("--crash-mode", choices=("abort", "signal"), default="abort")
    parser.add_argument("--without-native-capture", action="store_true", help="Expect no native-crash event from the comparison APK")
    args = parser.parse_args()
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
                return current

        current = wait(ready)
        if telemetry and role != "contender":
            wait(lambda: events(current, "probe.early-startup", "span"))
            wait(lambda: events(current, "probe.early-startup"))
            wait(lambda: events(current, "session.start"))
            for r in events(current, "probe.early-startup") + events(current, "probe.early-startup", "span"):
                assert r["resource"]["process.title"] == current["process"]
            assert current["duplicateFailure"] == "storage-unavailable"
        return current

    def record_path(role="main"):
        name = PACKAGE + (":" + role if role != "main" else "")
        return "no_backup/grafana-sessions/" + hashlib.sha256(name.encode()).hexdigest() + ".json"

    def stored(role="main"):
        return json.loads(shell("cat " + record_path(role)))

    def previous(current):
        matches = [r for r in events(current, "session.start") if r["attrs"]["session.id"] == current["session"]]
        assert len(matches) == 1, matches
        return matches[0]["attrs"].get("session.previous_id")

    def write(path, text):
        shell("printf %s " + shlex.quote(text) + " > " + shlex.quote(path))

    def passed(name, **details):
        results.append(dict(test=name, status="passed", **details))
        print("PASS " + name, flush=True)

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
        if args.crash_only:
            crash_check()
            return
        first = launch()
        assert previous(first) is None
        stop()
        second = launch()
        assert second["pid"] != first["pid"] and second["session"] != first["session"]
        assert previous(second) == first["session"]
        assert len(events(second, "session.start")) == 1 and not events(second, "session.end")
        assert stored()["id"] == second["session"]
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
        assert stored()["id"] == reset["session"]
        passed("reset order and in-flight span attribution", reset=reset)

        contender = launch("contender", telemetry=False)
        assert contender["storageFailure"] == "storage-unavailable" and contender["previous"] == ""
        assert stored()["id"] == reset["session"]
        worker = launch("worker")
        assert previous(worker) is None and worker["session"] != reset["session"]
        assert stored("worker")["id"] == worker["session"]
        stop()
        restarted = launch()
        worker2 = launch("worker")
        assert previous(restarted) == reset["session"] and previous(worker2) == worker["session"]
        passed("Android process chains and rejected competing owner", main=restarted, worker=worker2, contender=contender)

        stop()
        write(record_path(), "not json")
        corrupt = launch()
        assert previous(corrupt) is None and stored()["id"] == corrupt["session"]
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
        stop()
        before = shell("cat " + record_path())
        shell("chmod 400 " + record_path())
        shell("chmod 500 no_backup/grafana-sessions")
        try:
            failed = launch()
            assert failed["storageFailure"] == "write-failed"
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
