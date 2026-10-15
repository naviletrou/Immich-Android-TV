package nl.giejay.android.tv.immich.shared.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.Log
import nl.giejay.android.tv.immich.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the error that closed the app, and shows it at the next start, so the problem can be reported
 * from a TV without any developer tool.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date())
                val trace = Log.getStackTraceString(error).lines().filter { it.isNotBlank() }.take(MAX_LINES)
                File(appContext.filesDir, FILE).writeText("$date (${thread.name})\n" + trace.joinToString("\n"))
            } catch (_: Throwable) {
                // nothing more can be done
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Shows the error of the last crash once, if there was one. */
    fun showIfAny(activity: Activity) {
        val file = File(activity.filesDir, FILE)
        if (!file.exists()) return
        val text = try { file.readText() } catch (_: Exception) { "" }
        file.delete()
        if (text.isBlank()) return
        try {
            AlertDialog.Builder(activity)
                .setTitle(R.string.crash_title)
                .setMessage(activity.getString(R.string.crash_message, text))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (_: Exception) {
            // never block the start of the app
        }
    }

    private const val MAX_LINES = 40
}
