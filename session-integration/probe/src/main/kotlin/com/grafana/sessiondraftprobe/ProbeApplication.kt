package com.grafana.sessiondraftprobe

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.widget.TextView
import com.grafana.opentelemetry.android.ColdStartSessions
import com.grafana.opentelemetry.android.DraftSessionRuntime
import com.grafana.opentelemetry.android.DraftSessions
import com.grafana.opentelemetry.android.GrafanaOtelConfiguration
import io.opentelemetry.api.common.Attributes
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ProbeApplication : Application() {
    internal lateinit var runtime: DraftSessionRuntime
    lateinit var process: String
    lateinit var report: JSONObject

    override fun onCreate() {
        super.onCreate()
        process =
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
                .runningAppProcesses
                .first { it.pid == Process.myPid() }
                .processName
        report =
            JSONObject()
                .put("pid", Process.myPid())
                .put("process", process)
                .put("launch", UUID.randomUUID().toString())
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
            // A rejected duplicate must not release the live owner's kernel lock.
            ColdStartSessions(File(noBackupFilesDir, "grafana-sessions"), process).use {
                report.put("duplicateFailure", it.failure ?: "")
            }
        }
        saveReport()
    }

    fun saveReport() {
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
        report
            .put("beforeReset", before)
            .put("resetAccepted", accepted)
            .put("session", runtime.rum.sessionProvider.getSessionId())
            .put("storageFailure", runtime.storageFailure ?: "")
        saveReport()
    }
}

open class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ProbeApplication
        if (intent.getBooleanExtra("reset", false)) app.reset()
        app.report.put("actionToken", intent.getStringExtra("actionToken"))
        app.saveReport()
        setContentView(TextView(this).apply { text = app.report.toString(2) })
        if (intent.getBooleanExtra("nativeCrash", false)) {
            Handler(mainLooper).postDelayed({ Process.sendSignal(Process.myPid(), 6) }, 12000)
        }
    }
}

class WorkerActivity : ProbeActivity()

class ContenderActivity : ProbeActivity()
