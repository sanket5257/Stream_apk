package com.streamforge.app

import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streamforge.app.storage.StreamConfig
import com.streamforge.app.storage.StreamPrefs
import kotlinx.coroutines.launch

/**
 * Shared dialog for entering the YouTube stream key — the one real credential the
 * app needs to broadcast. Stored encrypted via StreamPrefs and used by the live
 * pipeline. Used from Home and Profile.
 */
object YoutubeKeyDialog {

    fun show(activity: AppCompatActivity, onSaved: () -> Unit = {}) {
        val prefs = StreamPrefs(activity)
        activity.lifecycleScope.launch {
            val current = prefs.load()
            val input = EditText(activity).apply {
                hint = "Paste your YouTube stream key"
                setText(current.streamKey)
                setSingleLine(true)
            }
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            val container = FrameLayout(activity).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            AlertDialog.Builder(activity)
                .setTitle("YouTube stream key")
                .setMessage("In YouTube Studio → Go Live → Stream settings, copy your Stream key and paste it here. It's stored only on your device (encrypted) and used to broadcast.")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->
                    val key = input.text?.toString()?.trim().orEmpty()
                    activity.lifecycleScope.launch {
                        val rtmp = current.rtmpUrl.ifBlank { StreamConfig.DEFAULT.rtmpUrl }
                        prefs.save(current.copy(streamKey = key, rtmpUrl = rtmp))
                        onSaved()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
