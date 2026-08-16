package com.streamforge.app.update

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.streamforge.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

        owner.lifecycleScope.launch {
            val result = UpdateManager.check()
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (result !is UpdateManager.CheckResult.Available) return@launch
            val release = result.release
            if (!release.mandatory &&
                prefs.getInt(KEY_SKIPPED_VERSION, -1) == release.versionCode
            ) return@launch
            offer(activity, release)
        }
    }

    /** Check because the user asked. Reports every outcome, including "you're up to date". */
    fun checkManually(activity: Activity, onFinished: () -> Unit = {}) {
        val owner = activity as? LifecycleOwner ?: return
        val progress = AlertDialog.Builder(activity)
            .setMessage("Checking for updates…")
            .setCancelable(false)
            .show()

        owner.lifecycleScope.launch {
            val result = UpdateManager.check()
            progress.dismiss()
            if (activity.isFinishing || activity.isDestroyed) return@launch
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
        dialog.show()
    }

    private fun startDownload(activity: Activity, release: UpdateManager.Release) {
        val owner = activity as? LifecycleOwner ?: return
        val progress = AlertDialog.Builder(activity)
            .setTitle("Downloading v${release.versionName}")
            .setMessage("0%")
            .setCancelable(false)
            .show()

        owner.lifecycleScope.launch {
            val file = UpdateManager.download(activity, release) { percent ->
                // download() reports from an IO thread; the dialog is main-thread only.
                owner.lifecycleScope.launch(Dispatchers.Main) {
                    progress.setMessage("$percent%")
                }
            }
            withContext(Dispatchers.Main) {
                progress.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                if (file == null) {
                    Toast.makeText(activity, "Download failed", Toast.LENGTH_LONG).show()
                    return@withContext
                }
                if (!UpdateManager.canRequestInstalls(activity)) {
                    // Android 8+ gates sideloading per source; send the user to grant it, then
                    // they can tap the update again.
                    AlertDialog.Builder(activity)
                        .setTitle("Allow installs")
                        .setMessage(
                            "Android needs your permission to install updates from StreamForge. " +
                                "Turn on \"Allow from this source\", then tap Update again."
                        )
                        .setPositiveButton("Open settings") { _, _ ->
                            activity.startActivity(UpdateManager.installPermissionIntent(activity))
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@withContext
                }
                UpdateManager.install(activity, file)
            }
        }
    }
}
