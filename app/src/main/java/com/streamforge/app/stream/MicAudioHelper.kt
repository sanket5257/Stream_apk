package com.streamforge.app.stream

import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import com.pedro.library.rtmp.RtmpCamera2
import java.lang.reflect.Field

/**
 * Phase 7: best-effort external-mic selection.
 *
 * RootEncoder's MicrophoneManager doesn't expose AudioDeviceInfo selection publicly.
 * We reach the underlying AudioRecord via reflection (the field is protected) and
 * call AudioRecord.setPreferredDevice() — the official API for routing input to a
 * specific USB / Bluetooth / wired mic.
 *
 * Must be called AFTER prepareAudio (which creates the AudioRecord) and BEFORE
 * startStream (which begins recording). Caller checks the boolean return — on
 * failure, the system default mic is used and the stream still works.
 */
object MicAudioHelper {
    private const val TAG = "MicAudioHelper"

    fun applyPreferredDevice(
        camera: RtmpCamera2,
        deviceId: Int,
        audioManager: AudioManager
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (deviceId <= 0) return false

        return try {
            val target = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == deviceId } ?: return false

            val micManager = findField(camera.javaClass, "microphoneManager")
                ?.apply { isAccessible = true }
                ?.get(camera) ?: return false

            val audioRecord = findField(micManager.javaClass, "audioRecord")
                ?.apply { isAccessible = true }
                ?.get(micManager) as? AudioRecord ?: return false

            audioRecord.preferredDevice = target
            Log.d(TAG, "Pinned mic to device ${target.id} (${target.productName})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not set preferred mic device: ${e.message}")
            false
        }
    }

    private fun findField(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }
}
