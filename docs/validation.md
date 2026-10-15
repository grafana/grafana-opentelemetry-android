# Extraction Validation

Initial local validation on October 9, 2026, with the October 14 review follow-up recorded below.
This is not release approval or Grafana Cloud ingestion evidence.

## Environment

- macOS arm64, OpenJDK 17.0.19.
- Gradle 9.3.1, AGP 9.1.1, Kotlin compiler 2.3.10.
- Android SDK Platform 37.0, Build Tools 37.0.0.
- OpenTelemetry Android BOM 1.7.0-alpha / agent 1.7.0, preserving the spike's dependencies.
- Android emulator 36.5.11: API 35 Google APIs arm64 and API 23 default arm64 images.
- Local OTLP receiver and instrumented Python backend; no cloud collector.

## Confirmed

### Source preservation

At the initial extraction, the complete `android/src` directory, including the four original test
classes, matched `Mobiles/android/grafana-opentelemetry-android/src` at `mobile-o11y-demo` revision
`fa6ddbb869696b3a62413dd3889ac7944018724d`. The review follow-up changes the semantic-convention
default to match upstream and adds regression tests; the source is no longer byte-for-byte
identical. Session behavior is unchanged.

### Library and public API

```sh
./gradlew --no-daemon :android:check :android:assembleDebug :android:assembleRelease \
  :android:publishReleasePublicationToTestRepository
```

Expected: unit tests, lint, API comparison and both AAR builds pass; Maven artifacts are written
only to the local test repository. Actual: passed, with 17 unit tests, zero test failures and no
lint errors. Lint reported dependency-version and version-catalog suggestions; versions were kept
aligned with the spike instead of upgraded during extraction.

The generated AAR, source JAR, POM and Gradle metadata were inspected. The POM exposes the upstream
BOM and agent; the AAR contains the Grafana configuration classes, not a bundled SDK fork.

Negative API test: temporarily renamed `getInstanceOrNull` and ran `:android:apiCheck`. It failed
with the exact removed/added signature. Restored the source, verified byte equality, and reran the
library checks successfully. The API baseline was not changed to accept the mutation.

### Independent consumer

```sh
./gradlew --no-daemon -p consumer assembleGrafanaDebug assembleGrafanaRelease \
  assembleUpstreamDebug assembleUpstreamRelease checkRemovalDependencies \
  lintGrafanaDebug lintUpstreamDebug
```

Expected: both flavors compile, including R8-minified releases, without a project dependency on the
library. Actual: all four APKs built. Both upstream runtime dependency graphs contain no
`com.grafana.opentelemetry` artifacts. Both variants use the identical `src/main` instrumentation;
only startup and dependencies differ. Consumer lint has no errors, with tool-version and test-app
manifest suggestions remaining.

### Runtime smoke test

After starting each dedicated emulator and building the APKs:

```sh
python consumer/smoke_test.py --adb /path/to/adb --serial emulator-PORT
```

Use the virtual environment and dependencies described in `consumer/README.md`.
Expected: every flavor/build-type pair exports a log and client span, and the instrumented local
backend span has the same trace ID and the client span as its parent. Actual: all four combinations
passed on API 23 arm64 and all four passed on API 35 arm64. Logs carried the matching trace ID.
The harness also verifies the service name and rejects an error status on the client span.

An initial receiver harness did not handle chunked request bodies. It was replaced with aiohttp
before the successful runs; no Android library fix was needed. Receivers and fixture apps were
stopped after testing, and the disposable emulator runs did not save snapshots.

### Workflow

`actionlint` 1.7.12 passed for `.github/workflows/validate.yml`. The Gradle commands used in the
workflow passed locally. At the time of the initial local validation, the workflow had not yet
run on GitHub in this extraction branch.

## October 14 Review Follow-Up

`useLatestExperimentalSemanticConventions` now defaults to `true`, matching the pinned
[upstream Android 1.7.0 configuration](https://github.com/open-telemetry/opentelemetry-android/blob/v1.7.0/android-agent/src/main/kotlin/io/opentelemetry/android/agent/dsl/SemanticConventionsConfiguration.kt).
An explicit `false` still selects legacy names, and the upstream configuration hook still runs
last. There are no dependency version or public API signature changes.

- Regression tests failed against the old default for both Kotlin and Java construction, then
  passed after the default changed. Tests check upstream crash/screen name mapping, explicit
  opt-out and the configuration hook; process-global convention state is restored after each test.
- Reran `:android:check :android:assembleDebug :android:assembleRelease --rerun-tasks` with the
  updated lockfile: all 19 unit tests, lint, API comparison and debug/release AAR builds passed.
  Also republished using `:android:publishReleasePublicationToTestRepository`, only locally.
- Rebuilt all four consumer APKs, including R8-minified releases, and reran removal dependency
  checks and consumer lint. A second build with `--refresh-dependencies` also passed.
- Reran `consumer/smoke_test.py` on API 23 and API 35 arm64 emulators: all eight combinations
  exported correlated logs, client spans and connected local backend spans.

These follow-up runs used the same local receiver setup above, not hosted Faro/Grafana or physical
devices. No package was released.

## Not Yet Verified

- Maven Central namespace access, signing and CI publishing credentials.
- A real remote package publication and installation from that registry.
- Faro acceptance and querying of the packaged consumer's telemetry in Grafana.
- Connected backend traces and removal checks in that same Grafana environment.
- Physical devices, other ABIs, or the complete supported Android API range.
- Production metrics routing, automatic OkHttp setup, or new session-management work.

The smoke fixture disables disk buffering for deterministic delivery and uses loopback HTTP. It
does not validate authenticated HTTPS cloud ingest, network retry/durability behavior or a full
production application. Existing unit tests cover the retained initialization and configuration
behavior; they do not replace these release gates.
