package com.grafana.opentelemetry.android

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.Incubating
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.agent.OpenTelemetryRumInitializer
import io.opentelemetry.android.internal.services.Services
import io.opentelemetry.android.internal.services.applifecycle.AppLifecycle
import io.opentelemetry.android.internal.services.applifecycle.ApplicationStateListener
import io.opentelemetry.android.session.Session
import io.opentelemetry.android.session.SessionObserver
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.sdk.common.Clock
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@OptIn(Incubating::class, ExperimentalGrafanaOtelApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class DraftSessionIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val prior = SavedSession("a".repeat(32), 100)
    private val lifecycleListeners = mutableListOf<ApplicationStateListener>()

    @Before fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val services = mockk<Services>(relaxed = true)
        val lifecycle = mockk<AppLifecycle>(relaxed = true)
        every { lifecycle.registerListener(any()) } answers {
            lifecycleListeners += firstArg<ApplicationStateListener>()
        }
        every { services.appLifecycle } returns lifecycle
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

    @Test fun `preview exports traces and logs with sampled and unsampled parents before and after reset`() {
        Receiver().use { receiver ->
            val runtime =
                DraftSessionInstaller().initialize(temporary.root, "preview") { configure ->
                    initializeIsolated(receiver.configuration, configure)
                }
            val expected = mutableMapOf<String, String>()
            try {
                val tracer = runtime.rum.openTelemetry.getTracer("preview")
                val logger =
                    runtime.rum.openTelemetry.logsBridge
                        .get("preview")
                val parents = mutableMapOf("root" to Context.root())
                for (remote in listOf(false, true)) {
                    for (sampled in listOf(false, true)) {
                        val flags = if (sampled) TraceFlags.getSampled() else TraceFlags.getDefault()
                        val parent =
                            if (remote) {
                                SpanContext.createFromRemoteParent("1".repeat(32), "2".repeat(16), flags, TraceState.getDefault())
                            } else {
                                SpanContext.create("1".repeat(32), "2".repeat(16), flags, TraceState.getDefault())
                            }
                        parents["remote-$remote-sampled-$sampled"] =
                            Context.root().with(
                                io.opentelemetry.api.trace.Span
                                    .wrap(parent),
                            )
                    }
                }
                repeat(2) { phase ->
                    val session = runtime.rum.sessionProvider.getSessionId()
                    for ((name, parent) in parents) {
                        val label = "preview-$phase-$name"
                        expected[label] = session
                        val span = tracer.spanBuilder(label).setParent(parent).startSpan()
                        try {
                            assertTrue("$label must be recorded", span.isRecording)
                            assertTrue("$label must be sampled", span.spanContext.isSampled)
                            logger
                                .logRecordBuilder()
                                .setContext(parent.with(span))
                                .setBody(label)
                                .emit()
                        } finally {
                            span.end()
                        }
                    }
                    runtime.rum.openTelemetry
                        .getMeter("preview")
                        .counterBuilder("preview.counter")
                        .build()
                        .add(1)
                    if (phase == 0) assertTrue(runtime.startNewSession())
                }
                tracer
                    .spanBuilder("after-reset")
                    .setNoParent()
                    .startSpan()
                    .end()
                logger.logRecordBuilder().setBody("after-reset").emit()
            } finally {
                runtime.close()
            }
            assertTrue(receiver.received.await(10, SECONDS))
            assertEquals(2, expected.values.toSet().size)
            assertEquals(
                expected,
                receiver.spans.filter { it.name.startsWith("preview-") }.associate {
                    it.name to
                        it.attributesList.value("session.id")
                },
            )
            assertEquals(
                expected,
                receiver.logs.filter { it.body.stringValue.startsWith("preview-") }.associate {
                    it.body.stringValue to
                        it.attributesList.value("session.id")
                },
            )
            assertEquals(expected.size, receiver.spans.count { it.name.startsWith("preview-") })
            assertEquals(expected.size, receiver.logs.count { it.body.stringValue.startsWith("preview-") })
            assertEquals(0, receiver.metricRequests.size)
        }
    }

    @Test fun `foreground idle time does not rotate until the four hour maximum`() {
        var now = 1_000_000_000L
        val clock = mockk<Clock>()
        every { clock.now() } answers { now }
        every { clock.nanoTime() } answers { now }
        Receiver().use { receiver ->
            val runtime =
                DraftSessionInstaller().initialize(temporary.root, "foreground") { configure ->
                    initializeIsolated(receiver.configuration) {
                        configure()
                        this.clock = clock
                    }
                }
            try {
                assertTrue(lifecycleListeners.isNotEmpty())
                lifecycleListeners.forEach { it.onApplicationForegrounded() }
                val started = now
                val session = runtime.rum.sessionProvider.getSessionId()
                for (elapsed in listOf(15.minutes, 1.hours, 4.hours - 1.milliseconds)) {
                    now = started + elapsed.inWholeNanoseconds
                    assertEquals(session, runtime.rum.sessionProvider.getSessionId())
                }
                now = started + 4.hours.inWholeNanoseconds
                runtime.rum.openTelemetry
                    .getTracer("preview")
                    .spanBuilder("after-reset")
                    .startSpan()
                    .end()
                runtime.rum.openTelemetry.logsBridge
                    .get("preview")
                    .logRecordBuilder()
                    .setBody("after-reset")
                    .emit()
                assertNotEquals(session, runtime.rum.sessionProvider.getSessionId())
            } finally {
                runtime.close()
            }
            assertTrue(receiver.received.await(10, SECONDS))
            val starts = receiver.logs.filter { it.eventName == "session.start" }
            assertEquals(2, starts.size)
            val current = starts.single { it.attributesList.value("session.previous_id") != null }
            assertEquals(
                current.attributesList.value("session.id"),
                receiver.spans
                    .single {
                        it.name == "after-reset"
                    }.attributesList
                    .value("session.id"),
            )
            assertEquals(
                current.attributesList.value("session.id"),
                receiver.logs
                    .single {
                        it.body.stringValue == "after-reset"
                    }.attributesList
                    .value("session.id"),
            )
        }
    }

    @Test fun `background expiry survives passive telemetry and is checked before foreground attribution`() {
        var now = 1_000_000_000L
        val clock = mockk<Clock>()
        every { clock.now() } answers { now }
        every { clock.nanoTime() } answers { now }
        Receiver().use { receiver ->
            val runtime =
                DraftSessionInstaller().initialize(temporary.root, "background") { configure ->
                    initializeIsolated(receiver.configuration) {
                        configure()
                        this.clock = clock
                    }
                }
            val first = runtime.rum.sessionProvider.getSessionId()
            try {
                assertTrue(lifecycleListeners.isNotEmpty())
                lifecycleListeners.forEach { it.onApplicationBackgrounded() }
                now += (15.minutes - 1.milliseconds).inWholeNanoseconds
                lifecycleListeners.forEach { it.onApplicationForegrounded() }
                assertEquals(first, runtime.rum.sessionProvider.getSessionId())
                now += 1.hours.inWholeNanoseconds
                assertEquals(first, runtime.rum.sessionProvider.getSessionId())
                lifecycleListeners.forEach { it.onApplicationBackgrounded() }
                val tracer = runtime.rum.openTelemetry.getTracer("preview")
                val logger =
                    runtime.rum.openTelemetry.logsBridge
                        .get("preview")
                now += 10.minutes.inWholeNanoseconds
                tracer.spanBuilder("background-passive").startSpan().end()
                logger.logRecordBuilder().setBody("background-passive").emit()
                assertEquals(first, runtime.rum.sessionProvider.getSessionId())
                now += 5.minutes.inWholeNanoseconds
                lifecycleListeners.forEach { it.onApplicationForegrounded() }
                tracer.spanBuilder("after-reset").startSpan().end()
                logger.logRecordBuilder().setBody("after-reset").emit()
                assertNotEquals(first, runtime.rum.sessionProvider.getSessionId())
            } finally {
                runtime.close()
            }
            assertTrue(receiver.received.await(10, SECONDS))
            assertEquals(
                first,
                receiver.spans
                    .single { it.name == "background-passive" }
                    .attributesList
                    .value("session.id"),
            )
            assertEquals(
                first,
                receiver.logs
                    .single { it.body.stringValue == "background-passive" }
                    .attributesList
                    .value("session.id"),
            )
            val current =
                receiver.logs.single {
                    it.eventName == "session.start" && it.attributesList.value(
                        "session.previous_id",
                    ) == first
                }
            assertEquals(
                current.attributesList.value("session.id"),
                receiver.spans
                    .single {
                        it.name == "after-reset"
                    }.attributesList
                    .value("session.id"),
            )
            assertEquals(
                current.attributesList.value("session.id"),
                receiver.logs
                    .single {
                        it.body.stringValue == "after-reset"
                    }.attributesList
                    .value("session.id"),
            )
            assertEquals(2, receiver.logs.count { it.eventName == "session.start" })
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

    @Test fun `observer failure propagates after reset without silently retrying`() {
        val failure = IllegalStateException("observer failed")
        var fail = false
        var calls = 0
        val observer =
            object : SessionObserver {
                override fun onSessionStarted(
                    newSession: Session,
                    previousSession: Session,
                ) {
                    calls++
                    if (fail) throw failure
                }

                override fun onSessionEnded(session: Session) = Unit
            }
        Receiver().use { receiver ->
            val runtime =
                DraftSessionInstaller().initialize(temporary.root, "app") { configure ->
                    initializeIsolated(receiver.configuration) {
                        configure()
                        session { observers(observer) }
                    }
                }
            try {
                val original = runtime.rum.sessionProvider.getSessionId()
                val callsBeforeReset = calls
                fail = true
                assertSame(failure, assertThrows(IllegalStateException::class.java) { runtime.startNewSession() })
                val replacement = runtime.rum.sessionProvider.getSessionId()
                assertNotEquals(original, replacement)
                assertEquals(callsBeforeReset + 1, calls)
                assertEquals(replacement, runtime.rum.sessionProvider.getSessionId())
                assertEquals(callsBeforeReset + 1, calls)

                fail = false
                assertTrue(runtime.startNewSession())
                assertNotEquals(replacement, runtime.rum.sessionProvider.getSessionId())
                assertEquals(callsBeforeReset + 2, calls)
            } finally {
                runtime.close()
            }
        }
    }

    @Test fun `reset rejects a runtime without the incubating agent API`() {
        val rum = mockk<OpenTelemetryRum>(relaxed = true)
        DraftSessionRuntime(rum, ColdStartSessions(temporary.root, "app")).use { runtime ->
            val failure = assertThrows(IllegalStateException::class.java) { runtime.startNewSession() }
            assertTrue(failure.message!!.contains("incubating RUM"))
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
