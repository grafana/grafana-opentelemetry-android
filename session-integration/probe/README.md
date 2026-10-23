# Local Android session probe

This app compiles the same internal draft sources and Grafana initializer as
the JVM tests. It adds the pinned upstream native-crash module. It is not
included in the published package.

Use a disposable emulator with the standard `10.0.2.2` host route. The driver
refuses non-emulator serials, clears only `com.grafana.sessiondraftprobe`, changes
permissions only in that app's sandbox and deliberately calls native `abort()` in its
main process. It does not clear logcat, change device connectivity or send
anything to Grafana Cloud. Do not run two receivers on port 14318 at once.

Use Python 3.9 or newer. After preparing the local upstream repository in
[the parent README](../README.md):

```sh
"$UPSTREAM/gradlew" -p session-integration :probe:assembleDebug
"$ANDROID_HOME/platform-tools/adb" -s "$SERIAL" install -r \
  session-integration/probe/build/outputs/apk/debug/probe-debug.apk
python3 -m venv /tmp/session57-probe-python
/tmp/session57-probe-python/bin/pip install opentelemetry-proto==1.38.0
/tmp/session57-probe-python/bin/python session-integration/probe/verify.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial "$SERIAL" \
  --apk session-integration/probe/build/outputs/apk/debug/probe-debug.apk \
  --evidence session-integration/build/evidence/new-run
```

Use a new evidence directory each time. Commands run from the package repo root.
`environment.json`, source/APK hashes, `results.json`, exported OTLP records and
logcat are retained even if a case fails. The driver pulls the installed APK and
checks its hash against `--apk` before clearing data. The receiver binds only
host loopback and shuts down when the driver exits. Test installation remains on the emulator;
uninstall that package after saving the evidence.

The cases cover restart links, early telemetry, reset order, an in-flight span,
two session-owning processes, a third competing writer, corrupt/future records,
real write denial, last-durable linking, buffered replay across process death
and native-crash replay after reset. An unavailable receiver returns HTTP 503;
this exercises export failure, not airplane mode. Cache files must exist before
the process is stopped. The restarted main process must not drain the worker's
buffer.

Persistence is asynchronous. Restart cases wait for the expected record before
stopping the process; this does not prove durability immediately after reset.
The probe refreshes its report while the Activity exists so the driver can wait
for asynchronous write failures. An unavailable process name stops SDK setup
with an explicit error rather than using another process's storage.

Each telemetry-producing launch also checks a root span and all combinations
of local/remote, sampled/unsampled parents. The receiver requires one span and
one correlated log per case, with the same session ID and sampled flag. The
reset cases repeat this under the new session; buffered records must keep their
original attribution. The probe records a metric too, but any metric-export
request fails the run. These checks cover the draft's no-sampling private-preview
configuration, not the normal package's defaults.

Run the receiver's mutation checks with the same Python environment:

```sh
/tmp/session57-probe-python/bin/python -m unittest discover \
  -s session-integration/probe -p test_verify.py -v
```

The crash check requires natural process termination. The JNI fixture only calls
libc `abort()` outside a signal handler; it installs no handler and changes no
SDK capture code. A timeout is a failure, not permission to force-stop the
process and claim crash recovery passed. Final
cleanup may force-stop the test app after the failed result has been recorded.
Only main-process native-crash capture is enabled. This does not test native
stack frames, signal-handler interoperability or secondary-process crash capture.

## Release and crash comparison

Release is R8-minified, non-debuggable and signed with the local debug key for
installation. Build `:probe:assembleRelease`, install `probe-release.apk`, and
pass that file through `--apk`. Use `adb root` on a disposable userdebug emulator
and add `--root-files`. The driver then accesses the sandbox as the app UID
through `su`, since `run-as` refuses non-debuggable apps. Test injection has the
emulator's `su` SELinux context; the app itself still runs under its normal UID
and context. This is not physical-device or production-signing validation.

To isolate crash termination from native capture, build Debug with
`-PdisableNativeCrash=true`, install that APK, and run with `--crash-only
--without-native-capture`. This omits the native-crash module while keeping the
same session integration. Only this comparison permits unused lock entries;
remaining dependency versions stay pinned. The driver verifies that the APK
does not contain the native capture library and expects no native-crash event.
The small probe crash library remains present in both builds.
Build again without the property before returning to normal validation.

`--crash-only` also works with normal capture enabled. Add `--crash-mode signal`
to reproduce the original `Process.sendSignal(..., SIGABRT)` path instead of
native `abort()`. These are different triggers: Android 6's
[`abort()` implementation](https://android.googlesource.com/platform/bionic/+/android-6.0.1_r81/libc/bionic/abort.cpp)
also handles a returning signal handler. Keep their results separate. Both paths
require natural process termination and record `/proc` state before cleanup on
a timeout. A failure in the no-capture control does not establish that crash
replay works.

The emulator runs are local OTLP evidence, not hosted ingestion or UI validation.
