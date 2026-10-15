package com.grafana.opentelemetry.consumer

import android.app.Activity
import android.os.Bundle
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runId = intent.getStringExtra("probe_id") ?: "manual"
        // Both variants use this exact instrumentation. Only startup and dependencies differ.
        Thread {
            val telemetry = (application as ConsumerApplication).rum.openTelemetry
            val span = telemetry.getTracer("package-consumer")
                .spanBuilder("package-consumer.request")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("test.run_id", runId)
                .startSpan()
            val connection = URL("http://10.0.2.2:8089/probe").openConnection() as HttpURLConnection
            try {
                span.makeCurrent().use {
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.setRequestProperty("X-Test-Run", runId)
                    telemetry.propagators.textMapPropagator.inject(Context.current(), connection) {
                        carrier, key, value -> carrier?.setRequestProperty(key, value)
                    }
                    span.setAttribute("http.response.status_code", connection.responseCode.toLong())
                    telemetry.logsBridge.get("package-consumer").logRecordBuilder()
                        .setBody("package-consumer.completed")
                        .setAttribute(AttributeKey.stringKey("test.run_id"), runId)
                        .emit()
                }
            } catch (error: Exception) {
                span.recordException(error)
                span.setStatus(StatusCode.ERROR)
            } finally {
                connection.disconnect()
                span.end()
            }
        }.start()
    }
}
