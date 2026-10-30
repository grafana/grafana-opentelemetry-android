package com.grafana.sessiondraftprobe

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = Application::class)
class ProcessNameTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun `missing process identity skips SDK initialization with an explicit report`() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setProcesses(emptyList())
        val application = ProbeApplication()
        ReflectionHelpers.callInstanceMethod<Void>(
            application,
            "attachBaseContext",
            ClassParameter.from(Context::class.java, context),
        )
        application.onCreate()
        assertFalse(application.hasRuntime)
        assertTrue(application.report.has("initializationError"))
        assertFalse(application.report.has("session"))
    }

    @Test fun `probe activity can be constructed before its context is attached`() {
        ProbeActivity()
    }

    @Test fun `missing or wrong activity service does not throw`() {
        for (service in listOf(null, Any())) {
            val unavailable =
                object : ContextWrapper(context) {
                    override fun getSystemService(name: String): Any? = service
                }
            assertNull(currentProcessName(unavailable))
        }
    }

    @Test fun `unavailable process information does not throw`() {
        val unavailable =
            object : ContextWrapper(context) {
                override fun getSystemService(name: String): Any = throw SecurityException("unavailable")
            }
        assertNull(currentProcessName(unavailable))
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setProcesses(emptyList())
        assertNull(currentProcessName(context))
    }

    @Test fun `current pid preserves the secondary process name`() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setProcesses(
            listOf(
                ActivityManager.RunningAppProcessInfo("app", Process.myPid() + 1, arrayOf("app")),
                ActivityManager.RunningAppProcessInfo("app:worker", Process.myPid(), arrayOf("app")),
            ),
        )
        assertEquals("app:worker", currentProcessName(context))
        shadowOf(manager).setProcesses(
            listOf(ActivityManager.RunningAppProcessInfo(" ", Process.myPid(), arrayOf("app"))),
        )
        assertNull(currentProcessName(context))
    }
}
