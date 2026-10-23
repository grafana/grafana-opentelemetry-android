package com.grafana.sessiondraftprobe

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.TextView
import com.grafana.opentelemetry.android.ColdStartSessions
import com.grafana.opentelemetry.android.DraftSessionRuntime
import com.grafana.opentelemetry.android.DraftSessions
import com.grafana.opentelemetry.android.GrafanaOtelConfiguration
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ProbeApplication : Application() {
    internal lateinit var runtime: DraftSessionRuntime
    lateinit var process: String
    lateinit var report: JSONObject
    val hasRuntime: Boolean get() = ::runtime.isInitialized

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
            runtime =
                DraftSessions.initialize(
                    this,
                    GrafanaOtelConfiguration(
                        otlpEndpoint = "http://10.0.2.2:14318",
                        serviceName = "local-session-draft-probe",
                        diskBufferingEnabled = buffered,
                        resourceAttributes =
                            Attributes
                                .builder()
                                .put("probe.process", process)
                                .put("probe.pid", Process.myPid().toLong())
                                .put("probe.launch", report.getString("launch"))
                                .build(),
                    ),
                    process,
                )
            val session = runtime.rum.sessionProvider.getSessionId()
            runtime.rum.emitEvent("probe.early-startup")
            runtime.rum.openTelemetry
                .getTracer("probe")
                .spanBuilder("probe.early-startup")
                .startSpan()
                .end()
            report.put("session", session).put("storageFailure", runtime.storageFailure ?: "")
            checkSampling("startup")
            // A rejected duplicate must not release the live owner's kernel lock.
            ColdStartSessions(File(noBackupFilesDir, "grafana-sessions"), process).use {
                report.put("duplicateFailure", it.failure ?: "")
            }
        }
        saveReport()
    }

    fun saveReport() {
        if (hasRuntime) report.put("storageFailure", runtime.storageFailure ?: "")
        File(filesDir, "report-${process.substringAfter(':', "main")}.json").writeText(report.toString())
    }

    fun reset() {
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
                runtime.rum.openTelemetry
                    .getTracer("probe")
                    .spanBuilder(label)
                    .setParent(parent)
                    .startSpan()
            try {
                runtime.rum.openTelemetry.logsBridge
                    .get("probe")
                    .logRecordBuilder()
                    .setContext(parent.with(span))
                    .setEventName(label)
                    .emit()
            } finally {
                span.end()
            }
        }
        runtime.rum.openTelemetry
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
                if (app.hasRuntime && app.report.optString("storageFailure") != (app.runtime.storageFailure ?: "")) {
                    app.saveReport()
                }
                reporter.postDelayed(this, 200)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ProbeApplication
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
}

class WorkerActivity : ProbeActivity()

class ContenderActivity : ProbeActivity()
