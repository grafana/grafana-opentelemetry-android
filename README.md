# Grafana OpenTelemetry Android

Grafana configuration for the upstream [OpenTelemetry Android SDK](https://github.com/open-telemetry/opentelemetry-android).
Application instrumentation uses standard OpenTelemetry APIs; this library is not an SDK fork.

**Development only.** No artifact from this repository has been released to Maven Central.
`com.grafana.opentelemetry:android` is the proposed coordinate. The `0.0.0-dev` version below is
published only to a local directory for validation, not a supported release.

## Source

The implementation and unit tests are extracted unchanged from
[`mobile-o11y-demo` #110](https://github.com/grafana/mobile-o11y-demo/pull/110), using the module at
[source revision `fa6ddbb`](https://github.com/grafana/mobile-o11y-demo/tree/fa6ddbb869696b3a62413dd3889ac7944018724d/Mobiles/android/grafana-opentelemetry-android).
This repository adds a standalone build, API checks and a separate package consumer.
It does not add new session-management behavior.

## Usage

See [configuration and initialization](android/README.md) for requirements, defaults and the
experimental upstream configuration hook. The package uses the `1.7.0-alpha` OpenTelemetry Android
BOM, which selects `android-agent` `1.7.0`.

```kotlin
val rum = GrafanaOtel.initialize(
    application = this,
    configuration = GrafanaOtelConfiguration(
        otlpEndpoint = "https://collector.example/otlp/app-key",
        serviceName = "my-android-app",
    ),
)
val tracer = rum.openTelemetry.getTracer("com.example.app")
```

Initialize in `Application.onCreate`. The first initialization owns the process-wide runtime.
Metrics export is disabled; this package routes logs and traces to the configured endpoint.
Automatic OkHttp instrumentation is configured by the application, not this library.

## Build and Test

Use JDK 17, Android SDK Platform 37.0 and Build Tools 37.0.0. Set `ANDROID_HOME` to your SDK.
The wrapper uses Gradle 9.3.1 and the build uses AGP 9.1.1 with Kotlin 2.3.10.

```sh
./gradlew :android:check :android:assembleDebug :android:assembleRelease
./gradlew :android:publishReleasePublicationToTestRepository
./gradlew -p consumer assembleGrafanaDebug assembleGrafanaRelease \
  assembleUpstreamDebug assembleUpstreamRelease checkRemovalDependencies \
  lintGrafanaDebug lintUpstreamDebug
```

The publication task writes AAR, source JAR, POM and Gradle metadata to `build/test-repository`.
The [consumer](consumer/README.md) is a separate Gradle build. It resolves that Maven artifact,
not a project dependency or included build. Its upstream-only flavor uses the same application
instrumentation without a dependency on the Grafana layer.

`check` runs the unit tests, Android lint and binary API comparison. The API snapshot is generated
from the release AAR with JetBrains' binary compatibility validator. AGP's built-in Kotlin support
requires explicit task wiring. For an intentional API change, run `./gradlew :android:apiDump` and
review the changes to `android/api/android.api` together with the code change. CI never updates the
baseline automatically. This initial snapshot is not a promise of stable APIs before release.

## Before a Release

- Confirm Maven namespace ownership, signing and CI publishing access.
- Agree the first release version, ownership and release workflow.
- Validate the packaged consumer against Faro ingest and connected backend traces.
- Verify removal of the Grafana layer in that same environment.
- Cover supported Android API levels and at least one physical device.

Local builds, unit tests and the consumer smoke test do not establish Grafana Cloud ingestion or
production readiness. No publishing credentials or remote release workflow are included here.

## License

[Apache License 2.0](LICENSE).
