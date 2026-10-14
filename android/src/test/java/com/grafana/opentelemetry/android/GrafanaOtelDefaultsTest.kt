package com.grafana.opentelemetry.android

import io.opentelemetry.android.agent.dsl.DiskBufferingConfigurationSpec
import io.opentelemetry.android.agent.dsl.HttpExportConfiguration
import io.opentelemetry.android.agent.dsl.OpenTelemetryConfiguration
import io.opentelemetry.android.agent.dsl.SemanticConventionsConfiguration
import io.opentelemetry.android.agent.dsl.SessionConfiguration
import io.opentelemetry.android.config.OtelRumConfig
import io.opentelemetry.android.instrumentation.AndroidInstrumentationLoader
import io.opentelemetry.android.semconv.internal.SemconvCompat
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.resources.ResourceBuilder
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalGrafanaOtelApi::class)
class GrafanaOtelDefaultsTest {
    private var originalSemanticConventions = true

    @Before
    fun saveSemanticConventions() {
        originalSemanticConventions = SemconvCompat.useLatestExperimental
    }

    @After
    fun restoreSemanticConventions() {
        SemconvCompat.useLatestExperimental = originalSemanticConventions
    }

    @Test
    fun `uses upstream experimental semantic conventions by default`() {
        val settings =
            GrafanaOtelConfiguration(
                otlpEndpoint = "https://collector.example.test/otlp/app-key",
                serviceName = "quickpizza-android",
            )
        val harness = newUpstreamConfiguration()
        val semanticConventions =
            harness.configuration.field<SemanticConventionsConfiguration>("semanticConventions")

        assertTrue(semanticConventions.useLatestExperimental)
        assertTrue(settings.useLatestExperimentalSemanticConventions)
        SemconvCompat.useLatestExperimental = false

        harness.configuration.applyGrafanaOtelConfiguration(settings)

        assertTrue(semanticConventions.useLatestExperimental)
        assertTrue(SemconvCompat.useLatestExperimental)
        assertEquals("app.crash", SemconvCompat.map("app.crash"))
        assertEquals("app.screen.name", SemconvCompat.map("app.screen.name"))
    }

    @Test
    fun `allows opting out of experimental semantic conventions`() {
        val harness = newUpstreamConfiguration()
        SemconvCompat.useLatestExperimental = true

        harness.configuration.applyGrafanaOtelConfiguration(
            GrafanaOtelConfiguration(
                otlpEndpoint = "https://collector.example.test/otlp/app-key",
                serviceName = "quickpizza-android",
                useLatestExperimentalSemanticConventions = false,
            ),
        )

        val semanticConventions =
            harness.configuration.field<SemanticConventionsConfiguration>("semanticConventions")
        assertFalse(semanticConventions.useLatestExperimental)
        assertFalse(SemconvCompat.useLatestExperimental)
        assertEquals("device.crash", SemconvCompat.map("app.crash"))
        assertEquals("screen.name", SemconvCompat.map("app.screen.name"))
    }

    @Test
    fun `maps every Grafana setting to the upstream configuration`() {
        val customKey = AttributeKey.stringKey("deployment.environment.name")
        val serviceNameKey = AttributeKey.stringKey("service.name")
        val settings =
            GrafanaOtelConfiguration(
                otlpEndpoint = "https://collector.example.test/otlp/app-key",
                serviceName = "quickpizza-android",
                headers = mapOf("Authorization" to "Bearer token"),
                serviceNamespace = "demo",
                serviceVersion = "1.2.3",
                resourceAttributes =
                    Attributes.builder()
                        .put(customKey, "staging")
                        .put(serviceNameKey, "should-be-overridden")
                        .build(),
                diskBufferingEnabled = false,
                useLatestExperimentalSemanticConventions = true,
                sessionBackgroundInactivityTimeout = Duration.ofSeconds(30),
                sessionMaxLifetime = Duration.ofHours(2),
            )
        val harness = newUpstreamConfiguration()

        harness.configuration.applyGrafanaOtelConfiguration(settings)

        val export = harness.configuration.field<HttpExportConfiguration>("exportConfig")
        assertEquals(settings.otlpEndpoint, export.baseUrl)
        assertEquals(settings.headers, export.baseHeaders)
        assertFalse(harness.rumConfig.getDiskBufferingConfig().enabled)
        assertFalse(harness.rumConfig.metricsEnabled)

        val semanticConventions =
            harness.configuration.field<SemanticConventionsConfiguration>("semanticConventions")
        assertTrue(semanticConventions.useLatestExperimental)

        val session = harness.configuration.field<SessionConfiguration>("sessionConfig")
        assertEquals(30.seconds, session.backgroundInactivityTimeout)
        assertEquals(2.hours, session.maxLifetime)

        val resourceBuilder = Resource.builder()
        harness.configuration
            .field<(ResourceBuilder) -> Unit>("resourceAction")
            .invoke(resourceBuilder)
        val resource = resourceBuilder.build()
        assertEquals("quickpizza-android", resource.getAttribute(serviceNameKey))
        assertEquals("demo", resource.getAttribute(AttributeKey.stringKey("service.namespace")))
        assertEquals("1.2.3", resource.getAttribute(AttributeKey.stringKey("service.version")))
        assertEquals("staging", resource.getAttribute(customKey))
    }

    @Test
    fun `applies the upstream escape hatch last`() {
        val settings =
            GrafanaOtelConfiguration(
                otlpEndpoint = "https://collector.example.test/otlp/app-key",
                serviceName = "quickpizza-android",
                sessionMaxLifetime = Duration.ofHours(2),
            )
        val harness = newUpstreamConfiguration()

        harness.configuration.applyGrafanaOtelConfiguration(settings) {
            httpExport { baseUrl = "https://override.example.test" }
            session { maxLifetime = 3.hours }
            semanticConventions { useLatestExperimental = false }
        }

        val export = harness.configuration.field<HttpExportConfiguration>("exportConfig")
        val session = harness.configuration.field<SessionConfiguration>("sessionConfig")
        assertEquals("https://override.example.test", export.baseUrl)
        assertEquals(3.hours, session.maxLifetime)
        assertFalse(SemconvCompat.useLatestExperimental)
    }

    @Test
    fun `keeps upstream resource attributes without losing required service attributes`() {
        val customKey = AttributeKey.stringKey("custom.resource")
        val settings =
            GrafanaOtelConfiguration(
                otlpEndpoint = "https://collector.example.test/otlp/app-key",
                serviceName = "quickpizza-android",
            )
        val harness = newUpstreamConfiguration()

        harness.configuration.applyGrafanaOtelConfiguration(settings) {
            resource {
                put(customKey, "additional")
                put(AttributeKey.stringKey("service.name"), "attempted-override")
            }
        }

        val resourceBuilder = Resource.builder()
        harness.configuration
            .field<(ResourceBuilder) -> Unit>("resourceAction")
            .invoke(resourceBuilder)
        val resource = resourceBuilder.build()
        assertEquals(
            "quickpizza-android",
            resource.getAttribute(AttributeKey.stringKey("service.name")),
        )
        assertEquals("additional", resource.getAttribute(customKey))
    }

    @Test
    fun `keeps metrics disabled when the escape hatch configures a metrics endpoint`() {
        val harness = newUpstreamConfiguration()

        harness.configuration.applyGrafanaOtelConfiguration(
            GrafanaOtelConfiguration(
                otlpEndpoint = "https://collector.example.test/otlp/app-key",
                serviceName = "quickpizza-android",
            ),
        ) {
            httpExport {
                metrics { url = "https://metrics.example.test/otlp" }
            }
        }

        assertFalse(harness.rumConfig.metricsEnabled)
    }

    @Test
    fun `enables disk buffering by default`() {
        val harness = newUpstreamConfiguration()

        harness.configuration.applyGrafanaOtelConfiguration(
            GrafanaOtelConfiguration(
                otlpEndpoint = "https://collector.example.test/otlp/app-key",
                serviceName = "quickpizza-android",
            ),
        )

        assertTrue(harness.rumConfig.getDiskBufferingConfig().enabled)
    }

    private fun newUpstreamConfiguration(): UpstreamConfigurationHarness {
        val rumConfig = OtelRumConfig()
        val diskBuffering =
            DiskBufferingConfigurationSpec::class.java
                .getDeclaredConstructor(OtelRumConfig::class.java)
                .apply { isAccessible = true }
                .newInstance(rumConfig)
        val instrumentationLoader =
            Proxy.newProxyInstance(
                AndroidInstrumentationLoader::class.java.classLoader,
                arrayOf(AndroidInstrumentationLoader::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getByType" -> null
                    "getAll" -> emptyList<Any>()
                    else -> error("Unexpected loader call: ${method.name}")
                }
            } as AndroidInstrumentationLoader
        val configuration =
            OpenTelemetryConfiguration::class.java
                .getDeclaredConstructor(
                    OtelRumConfig::class.java,
                    DiskBufferingConfigurationSpec::class.java,
                    Clock::class.java,
                    AndroidInstrumentationLoader::class.java,
                ).apply { isAccessible = true }
                .newInstance(rumConfig, diskBuffering, Clock.getDefault(), instrumentationLoader)

        return UpstreamConfigurationHarness(configuration, rumConfig)
    }

    private inline fun <reified T> Any.field(name: String): T =
        javaClass.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(this@field) as T
        }

    private data class UpstreamConfigurationHarness(
        val configuration: OpenTelemetryConfiguration,
        val rumConfig: OtelRumConfig,
    )
}
