package com.streamforge.app

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streamforge.app.storage.StreamConfig
import com.streamforge.app.storage.StreamPrefs
import kotlinx.coroutines.launch

/**
 * Shared dialog for entering the stream connection — the ingest/server URL plus the
 * stream key the app needs to broadcast. The URL defaults to YouTube's ingest but is
 * editable so you can stream to any RTMP endpoint. Stored encrypted via StreamPrefs
 * and used by the live pipeline. Used from Home and Profile.
 */
object YoutubeKeyDialog {

    fun show(activity: AppCompatActivity, onSaved: () -> Unit = {}) {
        val prefs = StreamPrefs(activity)
        activity.lifecycleScope.launch {
            val current = prefs.load()
            val density = activity.resources.displayMetrics.density
            val pad = (20 * density).toInt()
            val gap = (12 * density).toInt()

            val urlLabel = TextView(activity).apply { text = "Stream URL (RTMP ingest)" }
            val urlInput = EditText(activity).apply {
                hint = "rtmp://a.rtmp.youtube.com/live2/"
                setText(current.rtmpUrl.ifBlank { StreamConfig.DEFAULT.rtmpUrl })
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
            }

            val keyLabel = TextView(activity).apply {
                text = "Stream key"
                setPadding(0, gap, 0, 0)
            }
            val keyInput = EditText(activity).apply {
                hint = "Paste your stream key"
                setText(current.streamKey)
                setSingleLine(true)
            }

            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(urlLabel)
                addView(urlInput)
                addView(keyLabel)
                addView(keyInput)
            }

            AlertDialog.Builder(activity)
                .setTitle("Stream connection")
                .setMessage("Enter your RTMP ingest URL and stream key. For YouTube, keep the default URL and paste your Stream key from YouTube Studio → Go Live → Stream settings. Stored only on your device (encrypted).")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->
                    val url = urlInput.text?.toString()?.trim().orEmpty()
                        .ifBlank { StreamConfig.DEFAULT.rtmpUrl }
                    val key = keyInput.text?.toString()?.trim().orEmpty()
                    activity.lifecycleScope.launch {
                        prefs.save(current.copy(streamKey = key, rtmpUrl = url))
                        onSaved()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
