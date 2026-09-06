package com.streamforge.app.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Encrypted key/value storage for credentials (stream keys).
 *
 * androidx.security:security-crypto 1.1.0-alpha06 throws messageless
 * GeneralSecurityException / IllegalStateException whenever the AndroidKeystore alias and the
 * encrypted prefs file fall out of sync — typical after an app-data clear or reinstall, and on
 * some OEM ROMs. [open] detects that, wipes the corrupted state, and retries once.
 *
 * SECURITY: if the keystore is genuinely unusable on a device, every write here is a no-op and
 * [available] is false. Credentials are NEVER written in plaintext as a fallback; the user
 * re-enters them instead. Callers should surface that rather than pretending the save worked.
 *
 * This logic was previously inline in [StreamPrefs]. It lives here because destinations now
 * hold stream keys too, and two hand-copied versions of keystore self-healing is exactly the
 * kind of thing that drifts and then only fails on someone else's phone.
 */
class SecureStore(private val context: Context, private val fileName: String) {

    private val prefs: SharedPreferences? by lazy { open() }

    /** False when this device's keystore is unusable and nothing can be persisted. */
    val available: Boolean get() = prefs != null

    fun getString(key: String, default: String = ""): String = try {
        prefs?.getString(key, default) ?: default
    } catch (t: Throwable) {
        Log.w(TAG, "Reading $key failed; returning default", t)
        default
    }

    /**
     * Write a value. Returns false if it could not be persisted — commit() rather than apply()
     * precisely so the caller can tell.
     */
    fun putString(key: String, value: String): Boolean {
        val p = prefs
        if (p == null) {
            Log.e(TAG, "Encrypted prefs unavailable; '$key' not persisted")
            return false
        }
        return try {
            p.edit().putString(key, value).commit()
        } catch (t: Throwable) {
            Log.w(TAG, "Encrypted write failed; healing and retrying", t)
            clearKeystoreState()
            try {
                build().edit().putString(key, value).commit()
            } catch (t2: Throwable) {
                Log.e(TAG, "Encrypted write still failing; '$key' not persisted", t2)
                false
            }
        }
    }

    private fun open(): SharedPreferences? = try {
        build()
    } catch (t: Throwable) {
        Log.w(TAG, "EncryptedSharedPreferences init failed; clearing keystore state and retrying", t)
        clearKeystoreState()
        try {
            build()
        } catch (t2: Throwable) {
            Log.e(TAG, "EncryptedSharedPreferences unavailable on this device", t2)
            null
        }
    }

    private fun build(): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun clearKeystoreState() {
        try {
            context.deleteSharedPreferences(fileName)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to delete $fileName", t)
        }
        try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to clear keystore alias", t)
        }
    }

    private companion object {
        const val TAG = "SecureStore"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    }
}
