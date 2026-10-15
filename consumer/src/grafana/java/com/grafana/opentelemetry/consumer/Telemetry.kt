package com.grafana.opentelemetry.consumer

import android.app.Application
import com.grafana.opentelemetry.android.GrafanaOtel
import com.grafana.opentelemetry.android.GrafanaOtelConfiguration
import io.opentelemetry.android.OpenTelemetryRum

fun initializeTelemetry(application: Application): OpenTelemetryRum =
    GrafanaOtel.initialize(
        application,
        GrafanaOtelConfiguration(
            otlpEndpoint = "http://10.0.2.2:4318",
            serviceName = "package-consumer",
            diskBufferingEnabled = false,
        ),
    )
