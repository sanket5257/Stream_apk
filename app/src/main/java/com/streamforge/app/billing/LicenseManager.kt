package com.streamforge.app.billing

import android.content.Context
import android.util.Log
import com.streamforge.app.auth.AuthManager
import com.streamforge.app.auth.DeviceHelper
import com.streamforge.app.auth.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Licence activation and renewal, against the same Supabase project the invite codes use.
 *
 * HOW THIS WORKS, END TO END:
 *  1. A customer taps Upgrade, sees the plans, and contacts you (WhatsApp / call / email).
 *  2. You take payment however you like — there is no payment gateway in this app.
 *  3. You issue a code from the Supabase dashboard (see `LICENSE_SYSTEM_SETUP.sql` and
 *     `admin_tools/README.md`) and send it to them.
 *  4. They type it into Upgrade → "I have a licence code". The code is bound to their user id
 *     AND their device id, so passing it to a friend does not work.
 *  5. The app re-checks the licence on launch and caches the answer, with an offline grace
 *     window so a bad signal at a ground never downgrades a paying customer mid-match.
 *
 * Every call degrades to "leave the cached entitlement alone" rather than throwing. Licensing
 * is never allowed to be the reason the app won't open or a stream won't start.
 */
class LicenseManager(private val context: Context) {

    private val authManager = AuthManager(context)
    private val entitlements = EntitlementStore(context)
    private val supabase by lazy { SupabaseClient.client }

    sealed class Result {
        data class Success(val entitlement: Entitlement) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * Redeem a licence code for this user + device.
     *
     * The server does the real work — validating the code, checking it isn't already bound to
     * someone else, computing the expiry from the plan's duration, and marking it used — all
     * inside one SECURITY DEFINER function, so the app's anon key never needs write access to
     * the licences table.
     */
    suspend fun activate(rawCode: String): Result = withContext(Dispatchers.IO) {
        val code = rawCode.trim().uppercase().replace(" ", "")
        if (code.isBlank()) return@withContext Result.Failure("Enter your licence code.")

        val userId = authManager.userId()
            ?: return@withContext Result.Failure("Log in again before activating a licence.")
        val deviceId = DeviceHelper.getDeviceId(context)

        try {
            val response = supabase.postgrest.rpc(
                function = "app_activate_license",
                parameters = ActivateParams(userId = userId, deviceId = deviceId, code = code),
            ).decodeAs<LicenseRpcResponse>()

            if (response.status != STATUS_OK) {
                return@withContext Result.Failure(response.message ?: "That code isn't valid.")
            }

            val entitlement = response.toEntitlement(codeHint = code.takeLast(4))
            entitlements.save(entitlement)
            Log.d(TAG, "Licence activated: ${entitlement.tier}")
            Result.Success(entitlement)
        } catch (t: Throwable) {
            // A missing RPC (the SQL not yet installed), a network failure, or a schema
            // mismatch all land here. Say something a user can act on rather than leaking the
            // exception text.
            Log.e(TAG, "Licence activation failed", t)
            Result.Failure("Couldn't reach the licence server. Check your connection and try again.")
        }
    }

    /**
     * Re-check the licence in the background and refresh the cache.
     *
     * On any failure the cached entitlement is left exactly as it is — that is what the
     * offline grace window in [Entitlement] exists for.
     */
    suspend fun refresh(): Entitlement = withContext(Dispatchers.IO) {
        val cached = entitlements.current()
        val userId = authManager.userId() ?: return@withContext cached
        val deviceId = DeviceHelper.getDeviceId(context)

        try {
            val response = supabase.postgrest.rpc(
                function = "app_check_license",
                parameters = CheckParams(userId = userId, deviceId = deviceId),
            ).decodeAs<LicenseRpcResponse>()

            if (response.status != STATUS_OK) {
                // The server positively says there is no active licence — that IS an answer,
                // so drop to free rather than riding the grace window forever.
                val free = Entitlement(
                    tier = Tier.FREE,
                    checkedAtMs = System.currentTimeMillis(),
                    source = Entitlement.Source.NONE,
                )
                entitlements.save(free)
                return@withContext free
            }

            val entitlement = response.toEntitlement(codeHint = cached.codeHint)
            entitlements.save(entitlement)
            entitlement
        } catch (t: Throwable) {
            Log.w(TAG, "Licence check failed; keeping cached entitlement", t)
            cached
        }
    }

    private fun LicenseRpcResponse.toEntitlement(codeHint: String) = Entitlement(
        tier = Tier.fromServerValue(tier),
        expiresAtMs = parseTimestamp(expiresAt),
        checkedAtMs = System.currentTimeMillis(),
        source = Entitlement.Source.LICENSE,
        codeHint = codeHint,
    )

    /**
     * Parse the server's timestamp. Postgres hands back ISO-8601 in UTC; the fractional-second
     * part varies in length, so the seconds-precision format is tried after the full one
     * rather than assuming either.
     */
    private fun parseTimestamp(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val cleaned = value.trim().replace("T", " ").substringBefore("+")
        for (pattern in TIMESTAMP_PATTERNS) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return format.parse(cleaned)?.time ?: continue
            } catch (_: Exception) {
                // Try the next pattern.
            }
        }
        Log.w(TAG, "Couldn't parse licence expiry '$value'; treating as no expiry")
        return 0L
    }

    private companion object {
        const val TAG = "LicenseManager"
        const val STATUS_OK = "ok"
        val TIMESTAMP_PATTERNS = listOf(
            "yyyy-MM-dd HH:mm:ss.SSSSSS",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )
    }
}

// ---- RPC payloads. Field names map to the SQL function arguments. ----

@Serializable
private data class ActivateParams(
    @SerialName("p_user_id") val userId: String,
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_code") val code: String,
)

@Serializable
private data class CheckParams(
    @SerialName("p_user_id") val userId: String,
    @SerialName("p_device_id") val deviceId: String,
)

@Serializable
private data class LicenseRpcResponse(
    val status: String,
    val message: String? = null,
    val tier: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)
