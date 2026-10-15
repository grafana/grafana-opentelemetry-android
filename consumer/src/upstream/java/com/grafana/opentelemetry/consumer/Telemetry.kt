package com.grafana.opentelemetry.consumer

import android.app.Application
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.agent.OpenTelemetryRumInitializer

fun initializeTelemetry(application: Application): OpenTelemetryRum =
    OpenTelemetryRumInitializer.initialize(application) {
        httpExport { baseUrl = "http://10.0.2.2:4318" }
        resource { put("service.name", "package-consumer") }
        diskBuffering { enabled(false) }
        disableMetrics()
    }
