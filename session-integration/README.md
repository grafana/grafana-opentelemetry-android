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

The session guide is proposed in
[workbench PR #164](https://github.com/grafana/frontend-o11y-knowledge-workbench/pull/164).
This directory keeps the instructions needed to rebuild and run the probe.

## Upstream status

The previous-session getter from
[#2126](https://github.com/open-telemetry/opentelemetry-android/pull/2126) is merged.
Manual reset in [#2141](https://github.com/open-telemetry/opentelemetry-android/pull/2141)
depends on [#2145](https://github.com/open-telemetry/opentelemetry-android/pull/2145).
The reset method's Boolean result is still under discussion.

## Pins

Grafana package base: `eecda6a`.

- Upstream main: `0a44a34e8344ccc406379e6d3946bf048ba83199`.
- Extension API: `4614d37626b7fef1daa83e6fb0ecfbff2bc4405d`.
- Manual reset patch: `5882e8a25391727fe79f07276b548c825093ead4`.
- Local rebased reset: `7b30067f034e837baf7c01290786522423d9f7f8`.
- Local combined commit: `fec939a147047e34f3be70d6e961d39baf034ff5`.
- Reproducible source tree: `5cdd3e2462e8dd92ef520e2debc908e194a21d3f`.

The rebased and combined commits are local only. The commands below reproduce
their source tree from published commits. No extra upstream test patch is needed.
Do not use moving branch heads or count earlier prototype results as validation
of this combination.

## Run locally

Use JDK 21 and Android SDK 37. From this repository's root, start with a fresh
upstream checkout; the example directory must not already exist:

```sh
export DRAFT="$PWD/session-integration"
git clone https://github.com/open-telemetry/opentelemetry-android.git ../android-session-upstream-draft
export UPSTREAM="$(cd ../android-session-upstream-draft && pwd)"
git -C "$UPSTREAM" fetch origin 0a44a34e8344ccc406379e6d3946bf048ba83199 4614d37626b7fef1daa83e6fb0ecfbff2bc4405d 5882e8a25391727fe79f07276b548c825093ead4
git -C "$UPSTREAM" switch -c varanha/session-integration-local-base 4614d37626b7fef1daa83e6fb0ecfbff2bc4405d
git -C "$UPSTREAM" cherry-pick 5882e8a25391727fe79f07276b548c825093ead4
git -C "$UPSTREAM" merge --no-edit --no-ff 0a44a34e8344ccc406379e6d3946bf048ba83199
test "$(git -C "$UPSTREAM" rev-parse 'HEAD^{tree}')" = 5cdd3e2462e8dd92ef520e2debc908e194a21d3f
env -u CI "$UPSTREAM/gradlew" -p "$UPSTREAM" publishToMavenLocal \
  -Pversion=1.8.0-session57-draft \
  -Dmaven.repo.local="$DRAFT/build/upstream-repository" \
  --max-workers=2 --no-configuration-cache
"$UPSTREAM/gradlew" -p "$DRAFT" check assembleDebug assembleRelease koverXmlReport \
  :probe:assembleDebug :probe:assembleRelease --max-workers=2 --refresh-dependencies
"$UPSTREAM/gradlew" -p "$UPSTREAM" check koverXmlReport --max-workers=2 --no-configuration-cache
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
