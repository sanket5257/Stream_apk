package com.streamforge.app.billing

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

private val Context.entitlementDataStore: DataStore<Preferences> by preferencesDataStore(name = "entitlement_prefs")

/**
 * What this device is currently licensed for.
 *
 * Two clocks matter and they are NOT the same thing:
 *  - [expiresAtMs] is when the licence itself runs out. Past it, the user is back on free.
 *  - [checkedAtMs] is when we last confirmed the licence with the server. Because a streamer
 *    at a ground often has no usable signal, a confirmed licence keeps working offline for
 *    [OFFLINE_GRACE_MS] past its last check. That is a deliberate trade: someone whose licence
 *    was revoked keeps working for a few days, which is far cheaper than breaking a paying
 *    customer's live broadcast because the ground had no data.
 */
data class Entitlement(
    val tier: Tier = Tier.FREE,
    /** When the licence expires. 0 means "no expiry recorded" (free, or a lifetime licence). */
    val expiresAtMs: Long = 0L,
    /** When the server last confirmed this. */
    val checkedAtMs: Long = 0L,
    val source: Source = Source.NONE,
    /** Last four characters of the activated code, for display on the account screen. */
    val codeHint: String = "",
) {
    enum class Source { NONE, LICENSE }

    /** True while the licence itself is still within its term. */
    fun isWithinTerm(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtMs <= 0L || nowMs < expiresAtMs

    /** True while a cached paid entitlement is still inside its offline grace window. */
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        tier == Tier.FREE || nowMs - checkedAtMs < OFFLINE_GRACE_MS

    /** The tier to actually enforce right now. */
    fun effectiveTier(nowMs: Long = System.currentTimeMillis()): Tier =
        if (isWithinTerm(nowMs) && isFresh(nowMs)) tier else Tier.FREE

    /** Days left on the licence, or null when it has no expiry. */
    fun daysRemaining(nowMs: Long = System.currentTimeMillis()): Long? {
        if (expiresAtMs <= 0L) return null
        val remaining = expiresAtMs - nowMs
        return if (remaining <= 0) 0 else TimeUnit.MILLISECONDS.toDays(remaining)
    }

    companion object {
        /** Seven days. Long enough to cover a tournament weekend with bad connectivity. */
        val OFFLINE_GRACE_MS = TimeUnit.DAYS.toMillis(7)
    }
}

/**
 * Local cache of the entitlement.
 *
 * SECURITY NOTE — read before extending. This cache is a convenience, not a source of truth:
 * anything stored on the device can be edited by a determined user with a rooted phone. The
 * real check is [LicenseManager]'s server call, which binds a licence code to a user AND a
 * device id. For a direct-APK product sold to a known customer list, that combination is a
 * reasonable place to be — the realistic threat is code sharing between friends, which
 * device binding stops, not a determined reverse-engineer.
 */
class EntitlementStore(private val context: Context) {

    val flow: Flow<Entitlement> = context.entitlementDataStore.data
        .catch { t ->
            // A corrupt preferences file must not crash the app on launch; fall back to free.
            Log.e(TAG, "Reading entitlement failed", t)
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map { prefs ->
            Entitlement(
                tier = runCatching { Tier.valueOf(prefs[KEY_TIER] ?: Tier.FREE.name) }
                    .getOrDefault(Tier.FREE),
                expiresAtMs = prefs[KEY_EXPIRES_AT] ?: 0L,
                checkedAtMs = prefs[KEY_CHECKED_AT] ?: 0L,
                source = runCatching {
                    Entitlement.Source.valueOf(prefs[KEY_SOURCE] ?: Entitlement.Source.NONE.name)
                }.getOrDefault(Entitlement.Source.NONE),
                codeHint = prefs[KEY_CODE_HINT].orEmpty(),
            )
        }

    suspend fun current(): Entitlement = try {
        flow.first()
    } catch (t: Throwable) {
        Log.e(TAG, "Reading entitlement failed", t)
        Entitlement()
    }

    suspend fun save(entitlement: Entitlement) {
        try {
            context.entitlementDataStore.edit { prefs ->
                prefs[KEY_TIER] = entitlement.tier.name
                prefs[KEY_EXPIRES_AT] = entitlement.expiresAtMs
                prefs[KEY_CHECKED_AT] = entitlement.checkedAtMs
                prefs[KEY_SOURCE] = entitlement.source.name
                prefs[KEY_CODE_HINT] = entitlement.codeHint
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Saving entitlement failed", t)
        }
    }

    private companion object {
        const val TAG = "EntitlementStore"
        val KEY_TIER = stringPreferencesKey("tier")
        val KEY_EXPIRES_AT = longPreferencesKey("expires_at")
        val KEY_CHECKED_AT = longPreferencesKey("checked_at")
        val KEY_SOURCE = stringPreferencesKey("source")
        val KEY_CODE_HINT = stringPreferencesKey("code_hint")
    }
}
