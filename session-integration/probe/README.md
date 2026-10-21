# Local Android session probe

This app compiles the same internal draft sources and Grafana initializer as
the JVM tests. It adds the pinned upstream native-crash module. It is not
included in the published package.

Use a disposable emulator with the standard `10.0.2.2` host route. The driver
refuses non-emulator serials, clears only `com.grafana.sessiondraftprobe`, changes
permissions only in that app's sandbox and deliberately sends SIGABRT to its
main process. It does not clear logcat, change device connectivity or send
anything to Grafana Cloud. Do not run two receivers on port 14318 at once.

After preparing the local upstream repository in [the parent README](../README.md):

```sh
"$UPSTREAM/gradlew" -p session-integration :probe:assembleDebug
"$ANDROID_HOME/platform-tools/adb" -s "$SERIAL" install -r \
  session-integration/probe/build/outputs/apk/debug/probe-debug.apk
python3 -m venv /tmp/session57-probe-python
/tmp/session57-probe-python/bin/pip install opentelemetry-proto==1.38.0
/tmp/session57-probe-python/bin/python session-integration/probe/verify.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial "$SERIAL" \
  --evidence session-integration/build/evidence/new-run
```

Use a new evidence directory each time. Commands run from the package repo root.
`environment.json`, source/APK hashes, `results.json`, exported OTLP records and
logcat are retained even if a case fails. The receiver binds only host loopback,
and shuts down when the driver exits. Test installation remains on the emulator;
uninstall that package after saving the evidence.

The cases cover restart links, early telemetry, reset order, an in-flight span,
two session-owning processes, a third competing writer, corrupt/future records,
real write denial, last-durable linking, buffered replay across process death
and native-crash replay after reset. An unavailable receiver returns HTTP 503;
this exercises export failure, not airplane mode. Cache files must exist before
the process is stopped. The restarted main process must not drain the worker's
buffer.

The crash check requires natural process termination. A timeout is a failure,
not permission to force-stop the process and claim crash recovery passed. Final
cleanup may force-stop the test app after the failed result has been recorded.
Only main-process native-crash capture is enabled. This does not test native
stack frames, signal-handler interoperability or secondary-process crash capture.

Both APK variants build, but this driver installs Debug so `run-as` can inject
storage failures. It is not an R8 or release-runtime test. The emulator runs are
local OTLP evidence, not hosted ingestion, UI or physical-device validation.
