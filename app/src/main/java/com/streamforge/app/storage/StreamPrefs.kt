package com.streamforge.app.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.first
import java.security.KeyStore

// Top-level delegate: DataStore must be a process-wide singleton per file.
// Declaring it inside the class would create a new DataStore per instance and
// crash with "There are multiple DataStores active for the same file".
private val Context.streamPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "stream_prefs")

/**
 * Phase 2B: Persistent storage for stream configuration.
 * Uses DataStore for non-sensitive data and EncryptedSharedPreferences for the stream key.
 *
 * androidx.security:security-crypto:1.1.0-alpha06 is known to throw messageless
 * GeneralSecurityException / IllegalStateException when the AndroidKeystore alias
 * and the encrypted prefs file get out of sync (typical after app data clear or
 * reinstall, and on some OEM ROMs). encryptedPrefs() detects that, wipes the
 * corrupted state, and retries — only falling back to plain SharedPreferences if
 * the keystore is genuinely unusable on this device.
 */
class StreamPrefs(private val context: Context) {

    private val encryptedPrefs: SharedPreferences by lazy { openSecurePrefs() }

    private fun openSecurePrefs(): SharedPreferences {
        return try {
            buildEncryptedPrefs()
        } catch (e: Throwable) {
            Log.w(TAG, "EncryptedSharedPreferences init failed; clearing keystore state and retrying", e)
            clearSecureState()
            try {
                buildEncryptedPrefs()
            } catch (e2: Throwable) {
                Log.e(TAG, "EncryptedSharedPreferences still failing — falling back to plain prefs", e2)
                context.getSharedPreferences(PLAIN_FALLBACK_PREFS, Context.MODE_PRIVATE)
            }
        }
    }

    private fun buildEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearSecureState() {
        // Delete the encrypted prefs file so it gets rebuilt with the fresh master key.
        try {
            context.deleteSharedPreferences(SECURE_PREFS_FILE)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to delete $SECURE_PREFS_FILE", e)
        }
        // Drop the AndroidKeystore entry the previous MasterKey created.
        try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to clear keystore alias", e)
        }
    }

    companion object {
        private const val TAG = "StreamPrefs"
        private const val SECURE_PREFS_FILE = "secure_prefs"
        private const val PLAIN_FALLBACK_PREFS = "stream_prefs_plain"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

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
        val streamKey = try {
            encryptedPrefs.getString(KEY_STREAM_KEY, "") ?: ""
        } catch (e: Throwable) {
            Log.w(TAG, "Reading stream key failed; returning empty", e)
            ""
        }

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

        // Save sensitive stream key to encrypted preferences.
        // commit() (not apply) so a write-failure propagates as a return value we can react to.
        val committed = try {
            encryptedPrefs.edit().putString(KEY_STREAM_KEY, config.streamKey).commit()
        } catch (e: Throwable) {
            Log.w(TAG, "Encrypted write failed; healing and retrying", e)
            clearSecureState()
            // Retry once with a fresh keystore. Use the property again — the by lazy was already
            // resolved, so we manually rebuild here.
            try {
                buildEncryptedPrefs().edit().putString(KEY_STREAM_KEY, config.streamKey).commit()
            } catch (e2: Throwable) {
                Log.e(TAG, "Encrypted write still failing; persisting key to plain fallback", e2)
                context.getSharedPreferences(PLAIN_FALLBACK_PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_STREAM_KEY, config.streamKey).commit()
            }
        }
        if (!committed) Log.w(TAG, "Stream key commit returned false")
    }
}
