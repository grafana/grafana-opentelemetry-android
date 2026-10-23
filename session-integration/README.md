# Android session integration (draft)

This is a standalone experiment, not part of the package build or publication.
It compiles the real Grafana initializer with session persistence and manual
reset. The normal package's OTel 1.7.0 dependency and public API are unchanged.

Keep the code and probe on this draft branch until the upstream APIs are
released. The design notes, gap list and dated evidence are maintained separately
in the team workbench. This directory keeps the instructions needed to rebuild
and run the probe.

## Upstream status

The previous-session getter from
[#2126](https://github.com/open-telemetry/opentelemetry-android/pull/2126) is merged.
Manual reset in [#2141](https://github.com/open-telemetry/opentelemetry-android/pull/2141)
now depends on [#2145](https://github.com/open-telemetry/opentelemetry-android/pull/2145).

The tested snapshot below still uses the earlier #2141 revision based on
[#2037](https://github.com/open-telemetry/opentelemetry-android/pull/2037).
It has not been refreshed to #2145. Keep these pins when reproducing the recorded
results; updating the API requires another validation run.

## Pins

Grafana package base: `eecda6a`.

- Upstream main: `7731fe84f026100c70a57b37c43c922cfd92359f`.
- Previous-session getter: merged in main at `ade9714bda9c6b99ff43b034a19dcea942030e40`.
- Manual reset and extension API: `a304197eee7e1922e3095570955fc295dcb871f4`.
- Local combined commit: `6078b486a88212cef001f9fd023ef9f0781f35be`.
- Reproducible source tree: `0c96fb83aec465914b79bc30bf3b80c8429bc531`.

The combined commit is local only. Reproduce its source tree by merging the
reset pin into the pinned main below. Do not use moving branch heads or
count earlier prototype results as validation of this draft.

The combined tests also need [one mock update](upstream-test.patch): the reset
test must answer the new previous-session getter. This changes no upstream
production code and has not been applied to either remote PR.

## Run locally

Use JDK 21 and Android SDK 37. From this repository's root, start with a fresh
upstream checkout; the example directory must not already exist:

```sh
export DRAFT="$PWD/session-integration"
git clone https://github.com/open-telemetry/opentelemetry-android.git ../android-session-upstream-draft
export UPSTREAM="$(cd ../android-session-upstream-draft && pwd)"
git -C "$UPSTREAM" fetch origin 7731fe84f026100c70a57b37c43c922cfd92359f a304197eee7e1922e3095570955fc295dcb871f4
git -C "$UPSTREAM" switch -c varanha/session-integration-local-base 7731fe84f026100c70a57b37c43c922cfd92359f
git -C "$UPSTREAM" merge --no-edit --no-ff a304197eee7e1922e3095570955fc295dcb871f4
test "$(git -C "$UPSTREAM" rev-parse 'HEAD^{tree}')" = 0c96fb83aec465914b79bc30bf3b80c8429bc531
git -C "$UPSTREAM" apply "$DRAFT/upstream-test.patch"
env -u CI "$UPSTREAM/gradlew" -p "$UPSTREAM" publishToMavenLocal \
  -Pversion=1.8.0-session57-draft \
  -Dmaven.repo.local="$DRAFT/build/upstream-repository" \
  --max-workers=2 --no-configuration-cache
"$UPSTREAM/gradlew" -p "$DRAFT" check assembleDebug assembleRelease koverXmlReport --max-workers=2
"$UPSTREAM/gradlew" -p "$UPSTREAM" :android-agent:check :core:check :instrumentation:sessions:check --max-workers=1 --no-configuration-cache
```

Only the isolated local Maven directory can supply Android OTel dependencies.
Missing artifacts fail resolution rather than falling back to a released SDK.
The snapshot name is a local label, not a version available to customers.
Dependency locks pin the remaining libraries; they do not make snapshot artifacts immutable.

The JVM tests use Robolectric Android API 23 and 35 and a loopback OTLP receiver.
They exercise the actual Grafana initializer, emitted session links, existing
span attribution and logs with an explicit historical ID. They disable disk
buffering to isolate this path. File tests include malformed data, atomic-file
recovery, write failure and competing owners. None sends data to Grafana Cloud.

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

The accepted private-preview scope keeps traces and logs unsampled, metrics off,
a 15-minute background timeout and a four-hour maximum lifetime. Foreground
inactivity tracking and configurable cross-signal sampling remain separate.
This branch still uses the parent-based sampler: it can drop children of unsampled
parents, so it does not yet implement the accepted no-sampling configuration.
