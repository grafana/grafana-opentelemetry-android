package com.grafana.opentelemetry.android

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.Incubating
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.agent.OpenTelemetryRumInitializer
import io.opentelemetry.android.internal.services.Services
import io.opentelemetry.android.session.Session
import io.opentelemetry.android.session.SessionObserver
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.trace.v1.Span
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.zip.GZIPInputStream

@OptIn(Incubating::class, ExperimentalGrafanaOtelApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class DraftSessionIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val prior = SavedSession("a".repeat(32), 100)

    @Before fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val services = mockk<Services>(relaxed = true)
        every { services.appLifecycle } returns mockk(relaxed = true)
        every { services.visibleScreenTracker } returns mockk(relaxed = true)
        Services.set(services)
    }

    @After fun tearDown() {
        Services.set(null)
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun `Grafana initialization links restart and reset without relabeling old telemetry`() {
        val root = File(application.noBackupFilesDir, "grafana-sessions")
        ColdStartSessions(root, "app").use { it.save(prior) }
        Receiver().use { receiver ->
            val runtime = DraftSessions.initialize(application, receiver.configuration, "app")
            val first: String
            val second: String
            try {
                assertSame(runtime, DraftSessions.initialize(application, receiver.configuration, "ignored:worker"))
                assertFalse(ColdStartSessions.recordFile(root, "ignored:worker").exists())
                val late =
                    assertThrows(IllegalStateException::class.java) {
                        DraftSessionInstaller().initialize(root, "late") { configure ->
                            GrafanaOtel.initialize(application, receiver.configuration, configure)
                        }
                    }
                assertTrue(late.message!!.contains("before calling GrafanaOtel.initialize"))
                first = runtime.rum.sessionProvider.getSessionId()
                assertNotEquals(prior.id, first)
                val tracer = runtime.rum.openTelemetry.getTracer("session-draft")
                val logger =
                    runtime.rum.openTelemetry.logsBridge
                        .get("session-draft")
                val oldSpan = tracer.spanBuilder("before-reset").startSpan()
                runtime.rum.openTelemetry
                    .getMeter("session-draft")
                    .counterBuilder("draft.counter")
                    .build()
                    .add(1)
                logger.logRecordBuilder().setBody("before-reset").emit()
                assertTrue(runtime.startNewSession())
                second = runtime.rum.sessionProvider.getSessionId()
                assertNotEquals(first, second)
                oldSpan.end()
                logger
                    .logRecordBuilder()
                    .setBody("recovered-record")
                    .setAttribute(AttributeKey.stringKey("session.id"), prior.id)
                    .emit()
                tracer.spanBuilder("after-reset").startSpan().end()
                logger.logRecordBuilder().setBody("after-reset").emit()
                assertNull(runtime.storageFailure)
            } finally {
                runtime.close()
            }
            assertTrue(receiver.received.await(10, SECONDS))
            assertEquals(
                first,
                receiver.spans
                    .single { it.name == "before-reset" }
                    .attributesList
                    .value("session.id"),
            )
            assertEquals(
                second,
                receiver.spans
                    .single { it.name == "after-reset" }
                    .attributesList
                    .value("session.id"),
            )
            assertEquals(
                first,
                receiver.logs
                    .single { it.body.stringValue == "before-reset" }
                    .attributesList
                    .value("session.id"),
            )
            assertEquals(
                second,
                receiver.logs
                    .single { it.body.stringValue == "after-reset" }
                    .attributesList
                    .value("session.id"),
            )
            assertEquals(
                prior.id,
                receiver.logs
                    .single { it.body.stringValue == "recovered-record" }
                    .attributesList
                    .value("session.id"),
            )
            val starts = receiver.logs.filter { it.eventName == "session.start" }
            assertEquals(2, starts.size)
            assertEquals(
                prior.id,
                starts.single { it.attributesList.value("session.id") == first }.attributesList.value("session.previous_id"),
            )
            assertEquals(
                first,
                starts.single { it.attributesList.value("session.id") == second }.attributesList.value("session.previous_id"),
            )
            assertEquals(
                listOf(first),
                receiver.logs.filter { it.eventName == "session.end" }.map { it.attributesList.value("session.id") },
            )
            assertEquals(0, receiver.metricRequests.size)
            ColdStartSessions(root, "app").use { assertEquals(second, it.getPreviousSession()!!.id) }
            assertThrows(IllegalStateException::class.java) { runtime.startNewSession() }
        }
    }

    @Test fun `disk failure still delivers reset and its linked event`() {
        val root = temporary.newFolder("sessions")
        Receiver().use { receiver ->
            val runtime =
                DraftSessionInstaller().initialize(root, "app") { configure ->
                    initializeIsolated(receiver.configuration, configure)
                }
            val first = runtime.rum.sessionProvider.getSessionId()
            val second: String
            try {
                assertTrue(root.renameTo(File(temporary.root, "displaced")))
                root.writeText("unavailable")
                assertTrue(runtime.startNewSession())
                second = runtime.rum.sessionProvider.getSessionId()
                assertNotEquals(first, second)
                runtime.rum.openTelemetry.logsBridge
                    .get("test")
                    .logRecordBuilder()
                    .setBody("after-reset")
                    .emit()
                runtime.rum.openTelemetry
                    .getTracer("test")
                    .spanBuilder("after-reset")
                    .startSpan()
                    .end()
            } finally {
                runtime.close()
            }
            assertEquals("write-failed", runtime.storageFailure)
            assertTrue(receiver.received.await(10, SECONDS))
            assertEquals(
                first,
                receiver.logs
                    .single {
                        it.eventName == "session.start" && it.attributesList.value("session.id") == second
                    }.attributesList
                    .value("session.previous_id"),
            )
        }
    }

    @Test fun `a reset during observer delivery returns false without waiting or queueing`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        Receiver().use { receiver ->
            var block = false
            val observer =
                object : SessionObserver {
                    override fun onSessionStarted(
                        newSession: Session,
                        previousSession: Session,
                    ) {
                        if (block) {
                            entered.countDown()
                            check(release.await(10, SECONDS))
                        }
                    }

                    override fun onSessionEnded(session: Session) = Unit
                }
            val runtime =
                DraftSessionInstaller().initialize(temporary.root, "app") { configure ->
                    initializeIsolated(receiver.configuration) {
                        configure()
                        session { observers(observer) }
                    }
                }
            try {
                runtime.rum.sessionProvider.getSessionId()
                block = true
                val outer = executor.submit<Boolean> { runtime.startNewSession() }
                assertTrue(entered.await(10, SECONDS))
                val current = runtime.rum.sessionProvider.getSessionId()
                val concurrent = executor.submit<Boolean> { runtime.startNewSession() }
                assertFalse(concurrent.get(5, SECONDS))
                release.countDown()
                assertTrue(outer.get(10, SECONDS))
                assertEquals(current, runtime.rum.sessionProvider.getSessionId())
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, SECONDS))
                runtime.close()
            }
        }
    }

    @Test fun `initialization failure releases the writer and does not retry setup`() {
        val installer = DraftSessionInstaller()
        var configured = false
        Receiver().use { receiver ->
            assertThrows(IllegalStateException::class.java) {
                installer.initialize(temporary.root, "app") { configure ->
                    initializeIsolated(receiver.configuration) {
                        configure()
                        configured = true
                        error("stop during configuration")
                    }
                }
            }
            assertTrue(configured)
            ColdStartSessions(temporary.root, "app").use {
                assertNull(it.failure)
                it.save(prior)
            }
            assertThrows(IllegalStateException::class.java) {
                installer.initialize(temporary.root, "app") { throw AssertionError("must not retry") }
            }
        }
    }

    @Test fun `shutdown failure still closes storage and repeated close does not rerun shutdown`() {
        val storage = ColdStartSessions(temporary.root, "app")
        val rum = mockk<OpenTelemetryRum>()
        every { rum.shutdown() } throws IllegalStateException("shutdown failed")
        val runtime = DraftSessionRuntime(rum, storage)
        assertThrows(IllegalStateException::class.java) { runtime.close() }
        runtime.close()
        ColdStartSessions(temporary.root, "app").use { assertNull(it.failure) }
    }

    private class Receiver : AutoCloseable {
        val logs = ConcurrentLinkedQueue<LogRecord>()
        val spans = ConcurrentLinkedQueue<Span>()
        val metricRequests = ConcurrentLinkedQueue<String>()
        val received = CountDownLatch(2)
        private val server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val raw = checkNotNull(request.body).toByteArray().inputStream()
                            val input = if (request.headers["Content-Encoding"] == "gzip") GZIPInputStream(raw) else raw
                            input.use {
                                when (request.target) {
                                    "/v1/logs" -> {
                                        val batch =
                                            ExportLogsServiceRequest
                                                .parseFrom(it)
                                                .resourceLogsList
                                                .flatMap { resource -> resource.scopeLogsList }
                                                .flatMap { scope -> scope.logRecordsList }
                                        logs.addAll(batch)
                                        if (batch.any { log -> log.body.stringValue == "after-reset" }) received.countDown()
                                    }
                                    "/v1/traces" -> {
                                        val batch =
                                            ExportTraceServiceRequest
                                                .parseFrom(it)
                                                .resourceSpansList
                                                .flatMap { resource -> resource.scopeSpansList }
                                                .flatMap { scope -> scope.spansList }
                                        spans.addAll(batch)
                                        if (batch.any { span -> span.name == "after-reset" }) received.countDown()
                                    }
                                    "/v1/metrics" -> metricRequests.add(request.target)
                                }
                            }
                            return MockResponse(200)
                        }
                    }
                start()
            }
        val configuration = GrafanaOtelConfiguration(server.url("/").toString(), "session-draft", diskBufferingEnabled = false)

        override fun close() = server.close()
    }

    // Failure cases need fresh runtimes, not a reset of Grafana's process-wide singleton.
    private fun initializeIsolated(
        configuration: GrafanaOtelConfiguration,
        configure: GrafanaOtelUpstreamConfiguration.() -> Unit,
    ): OpenTelemetryRum =
        OpenTelemetryRumInitializer.initialize(application) {
            applyGrafanaOtelConfiguration(configuration, configure)
        }

    private fun List<KeyValue>.value(key: String): String? = find { it.key == key }?.value?.stringValue
}
