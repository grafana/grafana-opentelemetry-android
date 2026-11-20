package com.grafana.sessiondraftprobe

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.WindowManager
import android.widget.TextView
import com.grafana.opentelemetry.android.ColdStartSessions
import com.grafana.opentelemetry.android.DraftSessionRuntime
import com.grafana.opentelemetry.android.DraftSessions
import com.grafana.opentelemetry.android.ExperimentalGrafanaOtelApi
import com.grafana.opentelemetry.android.GrafanaOtel
import com.grafana.opentelemetry.android.GrafanaOtelConfiguration
import com.grafana.opentelemetry.android.configureSessionProcess
import io.opentelemetry.android.Incubating
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.samplers.Sampler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import android.content.Context as AndroidContext

@OptIn(ExperimentalGrafanaOtelApi::class, Incubating::class)
class ProbeApplication : Application() {
    internal lateinit var runtime: DraftSessionRuntime
    private lateinit var baselineRum: OpenTelemetryRum
    private val rum: OpenTelemetryRum get() = if (::baselineRum.isInitialized) baselineRum else runtime.rum
    lateinit var process: String
    lateinit var report: JSONObject
    val hasRuntime: Boolean get() = ::runtime.isInitialized || ::baselineRum.isInitialized
    val storageFailure: String? get() = if (::runtime.isInitialized) runtime.storageFailure else null
    internal val startupBaseline: Boolean get() = File(filesDir, "startup-baseline").exists()
    val realTimeEnabled: Boolean get() = File(filesDir, "realtime").exists()
    internal val collectorEndpoint: String
        get() = if (File(filesDir, "physical-device").exists()) "http://127.0.0.1:14318" else "http://10.0.2.2:14318"

    override fun onCreate() {
        super.onCreate()
        val processName = currentProcessName(this)
        process = processName ?: "unknown"
        report =
            JSONObject()
                .put("pid", Process.myPid())
                .put("process", process)
                .put("launch", UUID.randomUUID().toString())
        if (processName == null) {
            report.put("initializationError", "Cannot determine process name; session setup skipped")
            saveReport()
            return
        }
        if (process.endsWith(":contender")) {
            ColdStartSessions(File(noBackupFilesDir, "grafana-sessions"), packageName).use {
                report.put("storageFailure", it.failure ?: "").put("previous", it.getPreviousSession()?.id ?: "")
            }
        } else {
            val buffered = File(filesDir, "buffered").exists()
            val baseline = startupBaseline
            check(!baseline || (!buffered && !realTimeEnabled)) { "Startup control cannot run timeout or buffering tests" }
            val configuration =
                GrafanaOtelConfiguration(
                    otlpEndpoint = collectorEndpoint,
                    serviceName = "local-session-draft-probe",
                    diskBufferingEnabled = buffered,
                    resourceAttributes =
                        Attributes
                            .builder()
                            .put("probe.process", process)
                            .put("probe.pid", Process.myPid().toLong())
                            .put("probe.launch", report.getString("launch"))
                            .build(),
                )
            val setupStarted = SystemClock.elapsedRealtimeNanos()
            if (baseline) {
                // Match the draft configuration, changing only the persistence installation.
                baselineRum =
                    GrafanaOtel.initialize(this, configuration) {
                        sdkConfiguration.sampler { trace { Sampler.alwaysOn() } }
                        configureSessionProcess(this@ProbeApplication, process)
                    }
            } else {
                runtime = DraftSessions.initialize(this, configuration, process)
            }
            report.put("setupNanos", SystemClock.elapsedRealtimeNanos() - setupStarted)
            report.put("persistenceEnabled", !baseline)
            val session = rum.sessionProvider.getSessionId()
            rum.emitEvent("probe.early-startup")
            rum.openTelemetry
                .getTracer("probe")
                .spanBuilder("probe.early-startup")
                .startSpan()
                .end()
            report.put("session", session).put("storageFailure", storageFailure ?: "")
            checkSampling("startup")
            // A rejected duplicate must not release the live owner's kernel lock.
            if (!baseline) {
                ColdStartSessions(File(noBackupFilesDir, "grafana-sessions"), process).use {
                    report.put("duplicateFailure", it.failure ?: "")
                }
            }
        }
        saveReport()
    }

    fun saveReport() {
        if (hasRuntime) report.put("storageFailure", storageFailure ?: "")
        File(filesDir, "report-${process.substringAfter(':', "main")}.json").writeText(report.toString())
    }

    fun reset() {
        check(!::baselineRum.isInitialized) { "Startup control does not exercise reset" }
        val before = runtime.rum.sessionProvider.getSessionId()
        val span =
            runtime.rum.openTelemetry
                .getTracer("probe")
                .spanBuilder("probe.over-reset")
                .startSpan()
        runtime.rum.emitEvent("probe.before-reset")
        val accepted = runtime.startNewSession()
        span.end()
        runtime.rum.emitEvent("probe.after-reset")
        checkSampling("after-reset")
        report
            .put("beforeReset", before)
            .put("resetAccepted", accepted)
            .put("session", runtime.rum.sessionProvider.getSessionId())
            .put("storageFailure", runtime.storageFailure ?: "")
        saveReport()
    }

    fun recordLifecycle(state: String) {
        if (!realTimeEnabled) return
        val history = report.optJSONArray("lifecycle") ?: JSONArray()
        history.put(JSONObject().put("state", state).put("elapsedMs", SystemClock.elapsedRealtime()))
        report.put("lifecycle", history)
        saveReport()
    }

    fun checkpoint(token: String) {
        if (!realTimeEnabled || !hasRuntime) return
        val before = SystemClock.elapsedRealtime()
        val session = runtime.rum.sessionProvider.getSessionId()
        val name = "probe.realtime.$token"
        rum.emitEvent(name)
        rum.openTelemetry
            .getTracer("probe")
            .spanBuilder(name)
            .startSpan()
            .end()
        report.put(
            "checkpoint",
            JSONObject()
                .put("token", token)
                .put("session", session)
                .put("beforeMs", before)
                .put("afterMs", SystemClock.elapsedRealtime())
                .put("wallNanos", runtime.rum.clock.now()),
        )
        saveReport()
    }

    private fun checkSampling(phase: String) {
        val parents = mutableMapOf("root" to Context.root())
        for (remote in listOf(false, true)) {
            for (sampled in listOf(false, true)) {
                val flags = if (sampled) TraceFlags.getSampled() else TraceFlags.getDefault()
                val context =
                    if (remote) {
                        SpanContext.createFromRemoteParent("1".repeat(32), "2".repeat(16), flags, TraceState.getDefault())
                    } else {
                        SpanContext.create("1".repeat(32), "2".repeat(16), flags, TraceState.getDefault())
                    }
                parents["remote-$remote-sampled-$sampled"] = Context.root().with(Span.wrap(context))
            }
        }
        for ((name, parent) in parents) {
            val label = "probe.sampling.$phase.$name"
            val span =
                rum.openTelemetry
                    .getTracer("probe")
                    .spanBuilder(label)
                    .setParent(parent)
                    .startSpan()
            try {
                rum.openTelemetry.logsBridge
                    .get("probe")
                    .logRecordBuilder()
                    .setContext(parent.with(span))
                    .setEventName(label)
                    .emit()
            } finally {
                span.end()
            }
        }
        rum.openTelemetry
            .getMeter("probe")
            .counterBuilder("probe.sampling.counter")
            .build()
            .add(1)
    }
}

open class ProbeActivity : Activity() {
    private val reporter = Handler(Looper.getMainLooper())
    private val refresh =
        object : Runnable {
            override fun run() {
                val app = application as ProbeApplication
                if (app.hasRuntime && app.report.optString("storageFailure") != (app.storageFailure ?: "")) {
                    app.saveReport()
                }
                reporter.postDelayed(this, 200)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ProbeApplication
        if (app.realTimeEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (app.hasRuntime && intent.getBooleanExtra("reset", false)) app.reset()
        app.report.put("actionToken", intent.getStringExtra("actionToken"))
        val crashMode = intent.getStringExtra("crashMode") ?: "abort"
        app.report.put("crashMode", crashMode)
        app.saveReport()
        setContentView(TextView(this).apply { text = app.report.toString(2) })
        reporter.post(refresh)
        if (app.hasRuntime && intent.getBooleanExtra("nativeCrash", false)) {
            Handler(mainLooper).postDelayed({
                if (crashMode == "signal") Process.sendSignal(Process.myPid(), 6) else NativeCrash.abortNow()
            }, 12000)
        }
    }

    override fun onDestroy() {
        reporter.removeCallbacks(refresh)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        (application as ProbeApplication).recordLifecycle("foreground")
    }

    override fun onStop() {
        super.onStop()
        (application as ProbeApplication).recordLifecycle("background")
    }
}

// Explicit checkpoints avoid polling the session provider during an idle interval.
class CheckpointReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: AndroidContext,
        intent: Intent,
    ) {
        val token = intent.getStringExtra("checkpoint") ?: return
        (context.applicationContext as ProbeApplication).checkpoint(token)
    }
}

class WorkerActivity : ProbeActivity()

class ContenderActivity : ProbeActivity()
