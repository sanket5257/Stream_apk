package com.streamforge.app.storage

import android.content.Context
import android.util.Log
import com.streamforge.app.stream.Destination
import com.streamforge.app.stream.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent list of publish destinations.
 *
 * Stored whole, as JSON, inside [SecureStore] — because each destination carries a stream key,
 * which is a credential. Nothing here is ever written to DataStore in plaintext.
 */
class DestinationStore(context: Context) {

    private val secure = SecureStore(context.applicationContext, SECURE_FILE)
    private val legacyPrefs = StreamPrefs(context.applicationContext)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** False when the device keystore is unusable, so destinations can't be saved. */
    val canPersist: Boolean get() = secure.available

    /**
     * Load destinations, seeding a YouTube entry from the pre-multistream single stream key
     * on first run so existing users keep working without re-entering anything.
     */
    suspend fun load(): List<Destination> = withContext(Dispatchers.IO) {
        val raw = secure.getString(KEY_DESTINATIONS)
        if (raw.isNotBlank()) {
            val parsed = try {
                json.decodeFromString<List<Destination>>(raw)
            } catch (t: Throwable) {
                // A parse failure must not wipe the user's setup silently — log it and fall
                // through to the migration path, which at worst re-seeds from the legacy key.
                Log.e(TAG, "Stored destinations couldn't be parsed", t)
                null
            }
            if (parsed != null) return@withContext parsed
        }
        migrateFromLegacyStreamKey()
    }

    suspend fun save(destinations: List<Destination>): Boolean = withContext(Dispatchers.IO) {
        val encoded = try {
            json.encodeToString(destinations)
        } catch (t: Throwable) {
            Log.e(TAG, "Encoding destinations failed", t)
            return@withContext false
        }
        val ok = secure.putString(KEY_DESTINATIONS, encoded)
        // Keep the legacy single-key config in step with the primary YouTube destination.
        // StreamService and the update/notification paths still read StreamConfig, and a user
        // who edits their key here would otherwise see the old one everywhere else.
        if (ok) syncLegacyStreamKey(destinations)
        ok
    }

    suspend fun upsert(destination: Destination): Boolean {
        val current = load().toMutableList()
        val index = current.indexOfFirst { it.id == destination.id }
        if (index >= 0) current[index] = destination else current.add(destination)
        return save(current)
    }

    suspend fun remove(id: String): Boolean = save(load().filterNot { it.id == id })

    /**
     * Destinations that will actually be published to, in slot order.
     *
     * Capped at [MAX_ACTIVE]: the number of RTMP clients is fixed when the camera is
     * constructed (MultiRtpCamera2 takes a ConnectChecker array), so this cap and the array
     * length in StreamActivity must agree.
     */
    fun activeOf(destinations: List<Destination>): List<Destination> =
        destinations.filter { it.enabled && it.isConfigured }.take(MAX_ACTIVE)

    private suspend fun migrateFromLegacyStreamKey(): List<Destination> {
        val legacy = try {
            legacyPrefs.load()
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't read legacy stream config", t)
            null
        }
        val seeded = Destination(
            platform = Platform.YOUTUBE,
            ingestUrl = legacy?.rtmpUrl.orEmpty(),
            streamKey = legacy?.streamKey.orEmpty(),
            enabled = true,
        )
        // Persist the seed so the migration happens exactly once.
        if (seeded.streamKey.isNotBlank()) save(listOf(seeded))
        return listOf(seeded)
    }

    private suspend fun syncLegacyStreamKey(destinations: List<Destination>) {
        val primary = destinations.firstOrNull { it.platform == Platform.YOUTUBE && it.enabled }
            ?: destinations.firstOrNull { it.enabled }
            ?: return
        try {
            val current = legacyPrefs.load()
            legacyPrefs.save(
                current.copy(
                    rtmpUrl = primary.effectiveIngestUrl,
                    streamKey = primary.streamKey,
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't mirror the primary key into legacy config", t)
        }
    }

    companion object {
        private const val TAG = "DestinationStore"
        private const val SECURE_FILE = "secure_destinations"
        private const val KEY_DESTINATIONS = "destinations_json"

        /**
         * Maximum simultaneous RTMP destinations.
         *
         * Three is a deliberate ceiling, not a technical one: every extra destination
         * multiplies the uplink requirement (3 × 4.5 Mbps ≈ 13.5 Mbps sustained), and mobile
         * uplinks that can hold that are rare. Raising this without also solving bandwidth
         * would just produce three stuttering streams instead of one good one.
         */
        const val MAX_ACTIVE = 3
    }
}
