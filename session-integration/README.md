# Android session integration test probe

**Testing only. Do not merge this branch or PR into `main`.**

The `varanha/android-session-integration-draft` branch and
[PR #16](https://github.com/grafana/grafana-opentelemetry-android/pull/16)
keep the code, probe and review discussion together for
[#57](https://github.com/grafana/frontend-o11y-knowledge-workbench/issues/57).
The package integration will be a separate PR under `android/` once the required
upstream APIs are released. This probe is not intended to ship with it.

This is a standalone build, not part of the package build or publication.
It compiles the real Grafana initializer with session persistence and manual
reset. The normal package's OTel 1.7.0 dependency and public API are unchanged.

Session setup and private-preview scope are documented separately.
This directory keeps the instructions needed to rebuild and run the probe.

## Upstream status

The previous-session getter from
[#2126](https://github.com/open-telemetry/opentelemetry-android/pull/2126) is merged.
Manual reset [#2141](https://github.com/open-telemetry/opentelemetry-android/pull/2141)
is merged. The initializer still declares `OpenTelemetryRum` as its return type;
its returned object implements `IncubatingOpenTelemetryRum`, which provides
`startNewSession()`. The extension approach in
[#2145](https://github.com/open-telemetry/opentelemetry-android/pull/2145) was dropped.

A reset returns `false` while another transition is active; it is not queued.
Storage or observer exceptions propagate after the in-memory session changes,
so an exception does not mean the original session remains active. This probe
does not automatically retry either result and uses the initializer-owned session
provider, not a custom provider. Stop callers before shutdown.

## Pins

Grafana package base: `eecda6a`.

- Upstream merged source: `e13e9d8967e02accb3d47a43c8e0ea636876f4a1`.
- Manual reset merge: `cc2c7d504a093fb0c637b0ab6f01473e921e6744`.
- Source tree: `a63bfb88a586345185a68bf2505ffcc96c990fe8`.

No unmerged upstream patches are needed. This pin is not a released SDK.
Do not use moving branch heads or count earlier prototype results as validation
of this combination.

## Run locally

Use JDK 21 and Android SDK 37. From this repository's root, start with a fresh
upstream checkout; the example directory must not already exist:

```sh
export DRAFT="$PWD/session-integration"
git clone https://github.com/open-telemetry/opentelemetry-android.git ../android-session-upstream-draft
export UPSTREAM="$(cd ../android-session-upstream-draft && pwd)"
git -C "$UPSTREAM" checkout --detach e13e9d8967e02accb3d47a43c8e0ea636876f4a1
test "$(git -C "$UPSTREAM" rev-parse 'HEAD^{tree}')" = a63bfb88a586345185a68bf2505ffcc96c990fe8
env -u CI "$UPSTREAM/gradlew" -p "$UPSTREAM" publishToMavenLocal \
  -Pversion=1.9.0-session57-e13e9d89 \
  -Dmaven.repo.local="$DRAFT/build/upstream-repository" \
  --max-workers=2 --no-configuration-cache
"$UPSTREAM/gradlew" -p "$DRAFT" check assembleDebug assembleRelease koverXmlReport \
  :probe:assembleDebug :probe:assembleRelease --max-workers=2 --refresh-dependencies
"$UPSTREAM/gradlew" -p "$UPSTREAM" check koverXmlReport --max-workers=1 --no-configuration-cache
```

Only the isolated local Maven directory can supply Android OTel dependencies.
Missing artifacts fail resolution rather than falling back to a released SDK.
The snapshot name is a local label, not a version available to customers.
Dependency locks pin the remaining libraries; they do not make snapshot artifacts immutable.

The JVM tests use Robolectric Android API 23 and 35 and a loopback OTLP receiver.
They exercise the actual Grafana initializer, emitted session links, existing
span attribution and logs with an explicit historical ID. Clock-driven tests
check the background timeout and maximum lifetime, not real-time waits.
File tests include malformed data, atomic-file recovery, asynchronous write
failure and competing owners. None sends data to Grafana Cloud.

## October 30 validation

The pins above passed 91 JVM tests, lint, formatting, coverage checks and
Debug/Release builds. The R8 Release probe passed all eight scenarios on
arm64 API 23 and 35 emulators, including write failures, process isolation,
buffered replay and native `abort()` replay under the crashed session.

The pinned upstream's full check passed locally with one worker. Its first
two-worker run failed a `forceFlush` assertion in `DynamicHeadersExportTest`; that test then passed
in isolation. The failure's cause is not established. These results do not cover
full-duration timeouts, physical devices or hosted ingestion.

## Probe and limits

Follow the [probe instructions](probe/README.md) for emulator setup, Release
builds, failure injection and crash comparisons. Use a new evidence directory
for every run and retain failed runs as well as passing ones.

The repository's CI checks the normal package, not this standalone project.
Run the standalone checks above separately. The probe uses a local OTLP receiver;
it does not validate physical devices, hosted ingestion or the product UI.

Persistence is process-specific. Native-crash capture stays main-process only.
Saves are asynchronous; restart links use the last durable record, which may lag
after a failed or interrupted write. The initial read and ownership lock are
synchronous. Stop telemetry callers and close the runtime off the UI thread.

This standalone configuration keeps traces and logs unsampled, including spans
with unsampled parents. Metric export stays disabled. The 15-minute background
timeout and four-hour maximum lifetime remain unchanged. Foreground inactivity
tracking and configurable cross-signal sampling remain separate. The normal
package's sampling defaults are unchanged, and export can still fail or lose data.

Final validation must use the merged APIs in the supported package. Physical-device
storage/startup cost, full-duration timeouts and hosted ingestion/UI remain separate.
