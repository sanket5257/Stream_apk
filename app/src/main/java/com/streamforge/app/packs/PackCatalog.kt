package com.streamforge.app.packs

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Loads the graphics-pack definitions shipped in `assets/packs/`.
 *
 * Packs are data, not code, on purpose: a new scoreboard or lower third is a JSON file, so
 * the catalogue can grow without an app release — and later be served from a backend so packs
 * become a thing we can ship (or sell) continuously.
 *
 * Definitions are immutable and small, so they're parsed once and cached for the process.
 */
class PackCatalog private constructor(private val packs: List<GraphicsPack>) {

    val all: List<GraphicsPack> get() = packs

    fun byId(id: String): GraphicsPack? = packs.firstOrNull { it.id == id }

    fun byCategory(category: PackCategory): List<GraphicsPack> =
        packs.filter { it.category == category }

    /** Categories that actually have packs, in declaration order — no empty tabs. */
    fun categories(): List<PackCategory> =
        PackCategory.entries.filter { cat -> packs.any { it.category == cat } }

    companion object {
        private const val TAG = "PackCatalog"
        private const val PACKS_DIR = "packs"

        @Volatile
        private var cached: PackCatalog? = null

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            // A pack that omits an optional key must still parse — that is the whole point of
            // being able to add fields to the format later without breaking older definitions.
            coerceInputValues = true
        }

        suspend fun load(context: Context): PackCatalog {
            cached?.let { return it }
            return withContext(Dispatchers.IO) {
                val loaded = PackCatalog(readAll(context.applicationContext))
                cached = loaded
                loaded
            }
        }

        /**
         * Blocking load for the render path, where we're already off the main thread inside
         * the overlay renderer's IO work and cannot suspend.
         */
        fun loadBlocking(context: Context): PackCatalog {
            cached?.let { return it }
            val loaded = PackCatalog(readAll(context.applicationContext))
            cached = loaded
            return loaded
        }

        private fun readAll(context: Context): List<GraphicsPack> {
            val names = try {
                context.assets.list(PACKS_DIR)?.filter { it.endsWith(".json") }.orEmpty()
            } catch (t: Throwable) {
                Log.e(TAG, "Couldn't list $PACKS_DIR/", t)
                emptyList()
            }
            return names.mapNotNull { name -> readOne(context, name) }
                // Stable order regardless of the filesystem's: category first, then name.
                .sortedWith(compareBy({ it.category.ordinal }, { it.name }))
        }

        private fun readOne(context: Context, fileName: String): GraphicsPack? = try {
            val raw = context.assets.open("$PACKS_DIR/$fileName")
                .bufferedReader()
                .use { it.readText() }
            json.decodeFromString<GraphicsPack>(raw)
        } catch (t: Throwable) {
            // One malformed pack must not take the whole catalogue down — the rest still load
            // and the user sees every pack that IS valid.
            Log.e(TAG, "Skipping unreadable pack $fileName", t)
            null
        }
    }
}
