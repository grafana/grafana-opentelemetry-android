package com.grafana.sessiondraftprobe

import android.app.ActivityManager
import android.content.Context
import android.os.Process

internal fun currentProcessName(context: Context): String? =
    try {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
            ?.takeIf { it.isNotBlank() }
    } catch (_: RuntimeException) {
        null
    }
