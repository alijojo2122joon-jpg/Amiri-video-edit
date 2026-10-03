package com.amiri.cut

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline crash notes: Java crashes are written to a local file by an uncaught-exception
 * handler; native crashes / ANRs are read from the system's exit reasons on next start.
 * Nothing leaves the device — the user can copy the text if they want to share it.
 */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "crash/last.txt")
    private const val PREFS = "crashlog"

    fun install(app: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val f = file(app)
                f.parentFile?.mkdirs()
                f.writeText(header(app) + "Thread: ${t.name}\n\n" + sw.toString().take(12_000))
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun header(c: Context): String {
        val v = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull()
        return "Amiri Cut $v · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "\n"
    }

    /** A crash report from the previous run that the user hasn't seen yet, or null. */
    fun pending(c: Context): String? {
        val f = file(c)
        if (f.exists()) return runCatching { f.readText() }.getOrNull()
        if (Build.VERSION.SDK_INT < 30) return null
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong("seen", 0L)
        val am = c.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(c.packageName, 0, 5) }.getOrNull() ?: return null
        val last = info.firstOrNull { it.timestamp > seen &&
            (it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_CRASH || it.reason == ApplicationExitInfo.REASON_ANR) }
            ?: return null
        val kind = when (last.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_ANR -> "App not responding (ANR)"
            else -> "Crash"
        }
        val trace = if (last.reason == ApplicationExitInfo.REASON_ANR) runCatching {
            last.traceInputStream?.bufferedReader()?.use { it.readText().take(6_000) }
        }.getOrNull() else null
        return header(c) + "$kind in ${last.processName}\n" +
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(last.timestamp)) + "\n" +
            (last.description ?: "") + (trace?.let { "\n\n$it" } ?: "")
    }

    fun markSeen(c: Context) {
        runCatching { file(c).delete() }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("seen", System.currentTimeMillis()).apply()
    }
}
