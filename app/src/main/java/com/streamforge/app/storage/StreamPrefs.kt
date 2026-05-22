package com.streamforge.app.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.first

// Top-level delegate: DataStore must be a process-wide singleton per file.
// Declaring it inside the class would create a new DataStore per instance and
// crash with "There are multiple DataStores active for the same file".
private val Context.streamPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "stream_prefs")

/**
 * Phase 2B: Persistent storage for stream configuration.
 * Uses DataStore for non-sensitive data and EncryptedSharedPreferences for the stream key.
 */
class StreamPrefs(private val context: Context) {

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private val KEY_RTMP_URL = stringPreferencesKey("rtmp_url")
        private val KEY_BACKUP_RTMP_URL = stringPreferencesKey("backup_rtmp_url")
        private val KEY_WIDTH = intPreferencesKey("width")
        private val KEY_HEIGHT = intPreferencesKey("height")
        private val KEY_FPS = intPreferencesKey("fps")
        private val KEY_VIDEO_BITRATE = intPreferencesKey("video_bitrate_kbps")
        private val KEY_AUDIO_BITRATE = intPreferencesKey("audio_bitrate_kbps")
        private val KEY_PREFERRED_MIC = intPreferencesKey("preferred_mic_id")

        private const val KEY_STREAM_KEY = "stream_key"
    }

    /**
     * Load the stream configuration.
     * Returns DEFAULT config if no saved config exists.
     */
    suspend fun load(): StreamConfig {
        val preferences = context.streamPrefsDataStore.data.first()
        val streamKey = encryptedPrefs.getString(KEY_STREAM_KEY, "") ?: ""

        return StreamConfig(
            rtmpUrl = preferences[KEY_RTMP_URL] ?: StreamConfig.DEFAULT.rtmpUrl,
            streamKey = streamKey,
            width = preferences[KEY_WIDTH] ?: StreamConfig.DEFAULT.width,
            height = preferences[KEY_HEIGHT] ?: StreamConfig.DEFAULT.height,
            fps = preferences[KEY_FPS] ?: StreamConfig.DEFAULT.fps,
            videoBitrateKbps = preferences[KEY_VIDEO_BITRATE] ?: StreamConfig.DEFAULT.videoBitrateKbps,
            audioBitrateKbps = preferences[KEY_AUDIO_BITRATE] ?: StreamConfig.DEFAULT.audioBitrateKbps,
            backupRtmpUrl = preferences[KEY_BACKUP_RTMP_URL] ?: "",
            preferredMicId = preferences[KEY_PREFERRED_MIC] ?: 0
        )
    }

    /**
     * Save the stream configuration.
     */
    suspend fun save(config: StreamConfig) {
        // Save non-sensitive data to DataStore
        context.streamPrefsDataStore.edit { preferences ->
            preferences[KEY_RTMP_URL] = config.rtmpUrl
            preferences[KEY_BACKUP_RTMP_URL] = config.backupRtmpUrl
            preferences[KEY_WIDTH] = config.width
            preferences[KEY_HEIGHT] = config.height
            preferences[KEY_FPS] = config.fps
            preferences[KEY_VIDEO_BITRATE] = config.videoBitrateKbps
            preferences[KEY_AUDIO_BITRATE] = config.audioBitrateKbps
            preferences[KEY_PREFERRED_MIC] = config.preferredMicId
        }

        // Save sensitive stream key to encrypted preferences
        encryptedPrefs.edit()
            .putString(KEY_STREAM_KEY, config.streamKey)
            .apply()
    }
}
