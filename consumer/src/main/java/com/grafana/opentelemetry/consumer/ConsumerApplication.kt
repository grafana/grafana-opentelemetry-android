package com.grafana.opentelemetry.consumer

import android.app.Application
import io.opentelemetry.android.OpenTelemetryRum

class ConsumerApplication : Application() {
    lateinit var rum: OpenTelemetryRum
        private set

    override fun onCreate() {
        super.onCreate()
        rum = initializeTelemetry(this)
    }
}
