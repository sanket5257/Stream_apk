package com.streamforge.app.update

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import com.streamforge.app.BuildConfig
import com.streamforge.app.util.isUiAlive
import com.streamforge.app.util.safeLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The user-facing half of sideload updating: offer the release, show download progress, then
 * hand off to the system installer.
 *
 * Two entry points:
 *  - [checkManually] always says something back, so a "Check for updates" tap never looks dead.
 *  - [checkSilently] is for app launch: it only speaks up when there's actually an update, and
 *    at most once per [SILENT_INTERVAL_MS].
 */
object UpdateFlow {

    private const val TAG = "UpdateFlow"
    private const val PREFS = "update_prefs"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_SKIPPED_VERSION = "skipped_version_code"

    /** Don't nag: one background check every 12 hours is plenty for sideloaded builds. */
    private const val SILENT_INTERVAL_MS = 12 * 60 * 60 * 1000L

    /**
     * Check on launch. Silent when up to date, when offline, or when the user already chose
     * "Later" for this exact version — a sideloaded app that interrupts every cold start is
     * worse than one that updates a day late.
     */
    fun checkSilently(activity: Activity, now: Long = System.currentTimeMillis()) {
        if (!UpdateManager.isConfigured) return
        val owner = activity as? LifecycleOwner ?: return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < SILENT_INTERVAL_MS) return

        owner.safeLaunch(TAG, "update flow") {
            val result = UpdateManager.check()
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            if (!owner.isUiAlive()) return@safeLaunch
            if (result !is UpdateManager.CheckResult.Available) return@safeLaunch
            val release = result.release
            if (!release.mandatory &&
                prefs.getInt(KEY_SKIPPED_VERSION, -1) == release.versionCode
            ) return@safeLaunch
            offer(activity, release)
        }
    }

    /** Check because the user asked. Reports every outcome, including "you're up to date". */
    fun checkManually(activity: Activity, onFinished: () -> Unit = {}) {
        val owner = activity as? LifecycleOwner ?: return
        val progress = showSafely(
            AlertDialog.Builder(activity)
                .setMessage("Checking for updates…")
                .setCancelable(false)
        ) ?: return

        owner.safeLaunch(TAG, "update flow") {
            val result = UpdateManager.check()
            dismissSafely(progress)
            if (!owner.isUiAlive()) return@safeLaunch
            when (result) {
                is UpdateManager.CheckResult.Available -> offer(activity, result.release)
                is UpdateManager.CheckResult.UpToDate -> Toast.makeText(
                    activity,
                    "You're on the latest version (v${BuildConfig.VERSION_NAME})",
                    Toast.LENGTH_SHORT
                ).show()
                is UpdateManager.CheckResult.Failed -> Toast.makeText(
                    activity,
                    "Update check failed: ${result.reason}",
                    Toast.LENGTH_LONG
                ).show()
            }
            onFinished()
        }
    }

    private fun offer(activity: Activity, release: UpdateManager.Release) {
        val notes = release.notes.ifBlank { "A new version is available." }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Update to v${release.versionName}")
            .setMessage(notes)
            .setPositiveButton("Download") { _, _ -> startDownload(activity, release) }
            .setCancelable(!release.mandatory)
        if (!release.mandatory) {
            dialog.setNegativeButton("Later") { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_SKIPPED_VERSION, release.versionCode)
                    .apply()
            }
        }
        showSafely(dialog)
    }

    /**
     * Show a dialog, tolerating a window that's already gone.
     *
     * Every dialog here appears after a network round-trip, so the activity can be on its way
     * out by the time we get back — and a dialog on a dead window token throws
     * BadTokenException. An update prompt is never worth crashing over.
     */
    private fun showSafely(builder: AlertDialog.Builder): AlertDialog? = try {
        builder.show()
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "Couldn't show update dialog", t)
        null
    }

    /** Dismissing a dialog whose activity has already gone away throws; it's never important. */
    private fun dismissSafely(dialog: AlertDialog?) {
        try {
            dialog?.dismiss()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Couldn't dismiss update dialog", t)
        }
    }

    private fun startDownload(activity: Activity, release: UpdateManager.Release) {
        val owner = activity as? LifecycleOwner ?: return
        val progress = showSafely(
            AlertDialog.Builder(activity)
                .setTitle("Downloading v${release.versionName}")
                .setMessage("0%")
                .setCancelable(false)
        ) ?: return

        owner.safeLaunch(TAG, "update flow") {
            val file = UpdateManager.download(activity, release) { percent ->
                // download() reports from an IO thread; the dialog is main-thread only.
                owner.safeLaunch(TAG, "update progress") {
                    progress.setMessage("$percent%")
                }
            }
            withContext(Dispatchers.Main) {
                dismissSafely(progress)
                if (!owner.isUiAlive()) return@withContext
                if (file == null) {
                    Toast.makeText(activity, "Download failed", Toast.LENGTH_LONG).show()
                    return@withContext
                }
                if (!UpdateManager.canRequestInstalls(activity)) {
                    // Android 8+ gates sideloading per source; send the user to grant it, then
                    // they can tap the update again.
                    showSafely(
                        AlertDialog.Builder(activity)
                            .setTitle("Allow installs")
                            .setMessage(
                                "Android needs your permission to install updates from StreamForge. " +
                                    "Turn on \"Allow from this source\", then tap Update again."
                            )
                            .setPositiveButton("Open settings") { _, _ ->
                                // No install-sources screen on some OEM ROMs — don't crash on it.
                                try {
                                    activity.startActivity(UpdateManager.installPermissionIntent(activity))
                                } catch (t: Throwable) {
                                    android.util.Log.w(TAG, "No install-sources settings screen", t)
                                    Toast.makeText(
                                        activity,
                                        "Open Settings → Apps → StreamForge → Install unknown apps",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                    )
                    return@withContext
                }
                try {
                    UpdateManager.install(activity, file)
                } catch (t: Throwable) {
                    // FileProvider misconfiguration or a missing package installer.
                    android.util.Log.e(TAG, "Handing the APK to the installer failed", t)
                    Toast.makeText(activity, "Couldn't open the installer", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
