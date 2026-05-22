package com.streamforge.app.storage

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Phase 2B: Stream configuration data class.
 * Phase 4A: Made Parcelable for passing via Intent to StreamService.
 * Holds all settings for RTMP streaming to YouTube.
 */
@Parcelize
data class StreamConfig(
    val rtmpUrl: String,
    val streamKey: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateKbps: Int,
    val audioBitrateKbps: Int,
) : Parcelable {
    companion object {
        val DEFAULT = StreamConfig(
            rtmpUrl = "rtmp://a.rtmp.youtube.com/live2/",
            streamKey = "",
            width = 1280,
            height = 720,
            fps = 30,
            videoBitrateKbps = 3500,
            audioBitrateKbps = 128
        )
    }
}
