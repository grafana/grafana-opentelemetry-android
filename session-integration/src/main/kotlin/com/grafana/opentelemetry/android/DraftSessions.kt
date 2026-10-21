package com.grafana.opentelemetry.android

import android.app.Application
import io.opentelemetry.android.Incubating
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.agent.session.SessionExtension
import java.io.Closeable
import java.io.File

/** Local integration only. This is not part of the published package API. */
@OptIn(ExperimentalGrafanaOtelApi::class)
internal object DraftSessions {
    private val installer = DraftSessionInstaller()

    fun initialize(
        application: Application,
        configuration: GrafanaOtelConfiguration,
        processName: String,
    ): DraftSessionRuntime =
        installer.initialize(
            File(application.noBackupFilesDir, "grafana-sessions"),
            processName,
        ) { configure ->
            GrafanaOtel.initialize(application, configuration) {
                configure()
                configureSessionProcess(application, processName)
            }
        }
}

@OptIn(ExperimentalGrafanaOtelApi::class)
internal fun GrafanaOtelUpstreamConfiguration.configureSessionProcess(
    application: Application,
    processName: String,
) {
    resource { put("process.title", processName) }
    diskBuffering {
        telemetryStorageDir =
            File(
                application.cacheDir,
                "grafana-signals/" + ColdStartSessions.recordFile(application.cacheDir, processName).nameWithoutExtension,
            )
    }
    // Upstream crash storage is app-wide, even when session storage is process-specific.
    if (processName != application.applicationInfo.processName) {
        instrumentations { suppressing("native-crash") }
    }
}

@OptIn(ExperimentalGrafanaOtelApi::class, Incubating::class)
internal class DraftSessionInstaller {
    private val initialization = SingleInitialization<DraftSessionRuntime>()

    fun initialize(
        root: File,
        processName: String,
        initializeRum: (GrafanaOtelUpstreamConfiguration.() -> Unit) -> OpenTelemetryRum,
    ): DraftSessionRuntime =
        initialization.getOrInitialize {
            var storage: ColdStartSessions? = null
            try {
                val rum =
                    initializeRum {
                        check(storage == null) { "Session configuration ran more than once" }
                        val owner = ColdStartSessions(root, processName)
                        storage = owner
                        session { storage(owner) }
                    }
                DraftSessionRuntime(
                    rum,
                    checkNotNull(storage) {
                        "Initialize draft sessions before calling GrafanaOtel.initialize"
                    },
                )
            } catch (failure: Throwable) {
                storage?.close()
                throw failure
            }
        }
}

@OptIn(Incubating::class)
internal class DraftSessionRuntime(
    val rum: OpenTelemetryRum,
    private val storage: ColdStartSessions,
) : Closeable {
    @Volatile
    private var closed = false

    val storageFailure: String? get() = storage.failure

    /** The Boolean result is still under discussion upstream; false is not queued. */
    fun startNewSession(): Boolean {
        check(!closed) { "Restart the process after shutdown" }
        return checkNotNull(rum.getExtension(SessionExtension::class.java)) {
            "This draft requires the upstream SessionExtension"
        }.startNewSession()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            rum.shutdown()
        } finally {
            storage.close()
        }
    }
}
