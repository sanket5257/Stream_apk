package com.streamforge.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import android.widget.Toast
import com.streamforge.app.R

/**
 * Surfaces the previous session's crash, once, on the next launch.
 *
 * Without this a crash is invisible: the process dies, Android relaunches at the login screen,
 * and the user's only description is "it went back to home". Showing the report — and letting
 * them copy it — turns an unreportable failure into an actionable one.
 */
object CrashDialog {

    fun showIfCrashed(activity: AppCompatActivity) {
        val report = CrashReporter.readReport(activity) ?: return
        // Consume it immediately: shown once, whatever the user does with the dialog.
        CrashReporter.clear(activity)
        if (!activity.isUiAlive()) return

        // The first few lines are the summary (time, version, device, exception); the rest is
        // the frame-by-frame trace, which belongs on the clipboard, not in a dialog.
        val summary = report.lineSequence().take(SUMMARY_LINES).joinToString("\n")

        try {
            AlertDialog.Builder(activity)
                .setTitle(R.string.crash_dialog_title)
                .setMessage(activity.getString(R.string.crash_dialog_message, summary))
                .setPositiveButton(R.string.crash_dialog_copy) { _, _ -> copy(activity, report) }
                .setNegativeButton(R.string.crash_dialog_dismiss, null)
                .show()
        } catch (t: Throwable) {
            // Reporting a crash must never be the thing that causes the next one.
            android.util.Log.w("CrashDialog", "Couldn't show the crash report", t)
        }
    }

    private fun copy(context: Context, report: String) {
        try {
            context.getSystemService<ClipboardManager>()
                ?.setPrimaryClip(ClipData.newPlainText("StreamForge crash", report))
            Toast.makeText(context, R.string.crash_dialog_copied, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            android.util.Log.w("CrashDialog", "Couldn't copy the crash report", t)
        }
    }

    /** Header block plus the exception line and a couple of frames — enough to recognise it. */
    private const val SUMMARY_LINES = 9
}
