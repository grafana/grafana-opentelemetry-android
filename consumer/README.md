# Independent Package Consumer

This is a test fixture, not a production application. Build the local publication from the repository
root before building this directory. It is intentionally not included by the root Gradle settings.

- `grafana` resolves `com.grafana.opentelemetry:android:0.0.0-dev` from `../build/test-repository`.
- `upstream` resolves the upstream Android agent directly, with no Grafana dependency.
- `src/main` contains the identical application instrumentation for both flavors.
- Only the dependency and startup implementation differ. The upstream variant is a removal check,
  not a claim that all Grafana startup defaults are automatically retained.
- Both debug and minified release APKs use test signing. Do not distribute them as production apps.

On startup, the app emits a client span and a log through standard OpenTelemetry APIs. It sends a
request to `http://10.0.2.2:8089/probe` with W3C trace context, and exports OTLP to
`http://10.0.2.2:4318`. These are emulator-to-host loopback addresses. The manifest permits cleartext
HTTP only for this local fixture. Disk buffering is disabled here to make local delivery observable.

To test end-to-end traces, run an OTLP receiver and an instrumented backend at those addresses,
install either flavor and launch its `MainActivity`. Confirm the client's trace ID is connected to
the backend span, not merely that both services emit telemetry. Repeat for the minified build and
the upstream-only flavor. A local receiver is not evidence of successful Faro or Grafana Cloud ingest;
those need their own validation before release.

For a repeatable local check, start a dedicated emulator and leave host ports 4318 and 8089 free:

```sh
python3 -m venv build/smoke-venv
build/smoke-venv/bin/pip install -r consumer/smoke-requirements.txt
build/smoke-venv/bin/python consumer/smoke_test.py --serial emulator-5554
```

Run these commands from the repository root after building all four APKs. Add `--adb /path/to/adb`
if it is not on your path. The test binds loopback-only receivers, installs the fixture APKs,
checks that exported logs and client spans correlate with an instrumented local backend span, and
stops the fixture applications and servers afterward. It leaves the test APKs installed. Use a
disposable emulator; the script does not start or stop the emulator itself. It never contacts a
cloud collector.
