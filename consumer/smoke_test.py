"""Local emulator smoke test. Does not send telemetry to Grafana Cloud."""

import argparse
import asyncio
from pathlib import Path
import subprocess
import threading
import time
import uuid

from aiohttp import web
from google.protobuf.message import DecodeError
from opentelemetry import trace
from opentelemetry.proto.collector.logs.v1.logs_service_pb2 import ExportLogsServiceRequest
from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator


def attributes(values):
    return {value.key: value.value.string_value for value in values}


def run(adb, serial):
    spans, logs, requests = [], [], []
    lock = threading.Lock()
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    tracer = provider.get_tracer("package-consumer-backend")

    async def backend(request):
        context = TraceContextTextMapPropagator().extract(
            {key.lower(): value for key, value in request.headers.items()}
        )
        with tracer.start_as_current_span(
            "package-consumer.backend", context=context, kind=trace.SpanKind.SERVER
        ) as span:
            span.set_attribute("test.run_id", request.headers.get("X-Test-Run", ""))
        return web.Response()

    async def collect(request):
        with lock:
            requests.append((request.path, request.content_type))
        # aiohttp handles both chunked transfer encoding and gzip decompression.
        body = await request.read()
        try:
            if request.path == "/v1/traces":
                payload = ExportTraceServiceRequest.FromString(body)
                with lock:
                    for resource in payload.resource_spans:
                        for scope in resource.scope_spans:
                            spans.extend((resource.resource, span) for span in scope.spans)
            else:
                payload = ExportLogsServiceRequest.FromString(body)
                with lock:
                    for resource in payload.resource_logs:
                        for scope in resource.scope_logs:
                            logs.extend(scope.log_records)
        except DecodeError:
            raise web.HTTPBadRequest(text="Invalid OTLP protobuf")
        return web.Response(body=b"", content_type="application/x-protobuf")

    def device(*args):
        return subprocess.run(
            [adb, "-s", serial, *args], check=True, capture_output=True, text=True, timeout=60
        ).stdout

    application = web.Application(client_max_size=10 * 1024 * 1024)
    application.add_routes([
        web.get("/probe", backend),
        web.post("/v1/traces", collect),
        web.post("/v1/logs", collect),
    ])
    runner = web.AppRunner(application)
    loop = asyncio.new_event_loop()
    thread = threading.Thread(target=loop.run_forever, daemon=True)
    thread.start()

    async def start_servers():
        await runner.setup()
        for port in (4318, 8089):
            await web.TCPSite(runner, "127.0.0.1", port).start()

    installed = set()
    try:
        asyncio.run_coroutine_threadsafe(start_servers(), loop).result(timeout=10)
        for flavor in ("grafana", "upstream"):
            package = "com.grafana.opentelemetry.consumer." + flavor
            for build_type in ("debug", "release"):
                apk = Path(__file__).parent / "build/outputs/apk" / flavor / build_type
                apk /= f"package-consumer-{flavor}-{build_type}.apk"
                device("install", "-r", str(apk))
                installed.add(package)
                device("shell", "am", "force-stop", package)
                run_id = str(uuid.uuid4())
                device(
                    "shell", "am", "start", "-W", "-n",
                    package + "/com.grafana.opentelemetry.consumer.MainActivity",
                    "--es", "probe_id", run_id,
                )
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline:
                    with lock:
                        clients = [
                            (resource, span) for resource, span in spans
                            if span.name == "package-consumer.request"
                            and attributes(span.attributes).get("test.run_id") == run_id
                        ]
                        matching_logs = [
                            log for log in logs if log.body.string_value == "package-consumer.completed"
                            and attributes(log.attributes).get("test.run_id") == run_id
                        ]
                    backends = [
                        span for span in exporter.get_finished_spans()
                        if span.attributes.get("test.run_id") == run_id
                    ]
                    if clients and matching_logs and backends:
                        resource, client = clients[0]
                        backend = backends[0]
                        assert attributes(resource.attributes).get("service.name") == "package-consumer"
                        assert backend.context.trace_id == int.from_bytes(client.trace_id, "big")
                        assert backend.parent is not None
                        assert backend.parent.span_id == int.from_bytes(client.span_id, "big")
                        assert matching_logs[0].trace_id == client.trace_id
                        assert client.status.code != 2, "Client request reported an error"
                        print(f"PASS {flavor}/{build_type}: log, client span and connected backend span", flush=True)
                        break
                    time.sleep(0.5)
                else:
                    raise AssertionError(
                        f"Missing telemetry for {flavor}/{build_type}: "
                        f"requests={requests}, spans={[(s.name, attributes(s.attributes)) for _, s in spans]}, "
                        f"logs={[(log.body, attributes(log.attributes)) for log in logs]}, "
                        f"backend_spans={len(exporter.get_finished_spans())}"
                    )
                device("shell", "am", "force-stop", package)
    finally:
        try:
            for package in installed:
                device("shell", "am", "force-stop", package)
        finally:
            try:
                asyncio.run_coroutine_threadsafe(runner.cleanup(), loop).result(timeout=10)
            finally:
                loop.call_soon_threadsafe(loop.stop)
                thread.join(timeout=5)
                loop.close()
                provider.shutdown()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True, help="A dedicated emulator, not a physical device")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This fixture is configured for emulator-to-host loopback only")
    run(args.adb, args.serial)
