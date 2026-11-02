package com.grafana.opentelemetry.android

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.android.agent.dsl.DiskBufferingConfigurationSpec
import io.opentelemetry.android.agent.dsl.instrumentation.InstrumentationConfiguration
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.resources.ResourceBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalGrafanaOtelApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class DraftProcessConfigurationTest {
    @Test fun `processes get distinct buffers and only main keeps native crash capture`() {
        val app = RuntimeEnvironment.getApplication()
        val main = app.applicationInfo.processName
        val paths = mutableListOf<File>()
        listOf(main, "$main:worker").forEach { process ->
            val configuration = mockk<GrafanaOtelUpstreamConfiguration>()
            val disk = mockk<DiskBufferingConfigurationSpec>(relaxed = true)
            val instrumentation = mockk<InstrumentationConfiguration>(relaxed = true)
            val resource = Resource.builder()
            every { configuration.resource(any()) } answers { firstArg<ResourceBuilder.() -> Unit>().invoke(resource) }
            every { configuration.diskBuffering(any()) } answers {
                firstArg<DiskBufferingConfigurationSpec.() -> Unit>().invoke(disk)
            }
            every { configuration.instrumentations(any()) } answers {
                firstArg<InstrumentationConfiguration.() -> Unit>().invoke(instrumentation)
            }
            every { disk.telemetryStorageDir = any() } answers { paths.add(firstArg<File>()) }

            configuration.configureSessionProcess(app, process)

            assertEquals(process, resource.build().getAttribute(AttributeKey.stringKey("process.title")))
            verify(exactly = if (process == main) 0 else 1) { instrumentation.suppressing("native-crash") }
            verify(exactly = 0) { disk.enabled(any()) }
        }
        assertEquals(2, paths.size)
        assertNotEquals(paths[0], paths[1])
        paths.forEach { assertEquals(File(app.cacheDir, "grafana-signals"), it.parentFile) }
    }
}
