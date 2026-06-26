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
    /** Phase 7: optional fallback RTMP URL. Used when primary exhausts reconnect attempts. */
    val backupRtmpUrl: String = "",
    /** Phase 7: preferred mic input id from AudioDeviceInfo.getId(); 0 = system default. */
    val preferredMicId: Int = 0,
) : Parcelable {
    companion object {
        val DEFAULT = StreamConfig(
            rtmpUrl = "rtmp://a.rtmp.youtube.com/live2/",
            streamKey = "",
            width = 1920,
            height = 1080,
            fps = 30,
            // YouTube's recommended bitrate for 1080p30 is ~4.5–9 Mbps; 6 Mbps is a sharp default.
            videoBitrateKbps = 6000,
            audioBitrateKbps = 128
        )
    }
}
