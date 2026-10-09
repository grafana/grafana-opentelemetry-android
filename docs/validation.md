# Extraction Validation

Local validation on October 9, 2026. This is not release approval or Grafana Cloud ingestion evidence.

## Environment

- macOS arm64, OpenJDK 17.0.19.
- Gradle 9.3.1, AGP 9.1.1, Kotlin compiler 2.3.10.
- Android SDK Platform 37.0, Build Tools 37.0.0.
- OpenTelemetry Android BOM 1.7.0-alpha / agent 1.7.0, preserving the spike's dependencies.
- Android emulator 36.5.11: API 35 Google APIs arm64 and API 23 default arm64 images.
- Local OTLP receiver and instrumented Python backend; no cloud collector.

## Confirmed

### Source preservation

The complete `android/src` directory, including the four original test classes, matches
`Mobiles/android/grafana-opentelemetry-android/src` at `mobile-o11y-demo` revision
`fa6ddbb869696b3a62413dd3889ac7944018724d`. No SDK behavior or session features were changed.

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
workflow passed locally. The workflow has not been run on GitHub in this extraction branch.

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
