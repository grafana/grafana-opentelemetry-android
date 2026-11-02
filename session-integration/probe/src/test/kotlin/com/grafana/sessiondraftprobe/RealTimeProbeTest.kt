package com.grafana.sessiondraftprobe

import android.app.Application
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = Application::class)
class RealTimeProbeTest {
    @Test fun `physical device marker selects only the USB loopback collector`() {
        val context = RuntimeEnvironment.getApplication()
        val app = ProbeApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext", ClassParameter.from(Context::class.java, context))
        val marker = File(context.filesDir, "physical-device")
        marker.delete()
        try {
            assertEquals("http://10.0.2.2:14318", app.collectorEndpoint)
            marker.writeText("yes")
            assertEquals("http://127.0.0.1:14318", app.collectorEndpoint)
        } finally {
            marker.delete()
        }
    }

    @Test fun `lifecycle recording requires the real-time marker`() {
        val context = RuntimeEnvironment.getApplication()
        val app = ProbeApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext", ClassParameter.from(Context::class.java, context))
        app.process = context.packageName
        app.report = JSONObject()
        val marker = File(context.filesDir, "realtime")
        marker.delete()
        try {
            app.recordLifecycle("foreground")
            assertFalse(app.report.has("lifecycle"))
            marker.writeText("yes")
            app.recordLifecycle("foreground")
            app.recordLifecycle("background")
            val history = app.report.getJSONArray("lifecycle")
            assertEquals(2, history.length())
            assertEquals("foreground", history.getJSONObject(0).getString("state"))
            assertEquals("background", history.getJSONObject(1).getString("state"))
            assertTrue(history.getJSONObject(1).getLong("elapsedMs") >= history.getJSONObject(0).getLong("elapsedMs"))
            app.checkpoint("no-runtime")
            assertFalse(app.report.has("checkpoint"))
            assertTrue(File(context.filesDir, "report-main.json").exists())
        } finally {
            marker.delete()
        }
    }

    @Test fun `receiver ignores an intent without a checkpoint`() {
        CheckpointReceiver().onReceive(RuntimeEnvironment.getApplication(), Intent())
    }
}
