package com.streamforge.app.scenes

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Top-level delegate: DataStore must be a process-wide singleton per file. Declaring it inside
// the class would create a new DataStore per instance and crash with "There are multiple
// DataStores active for the same file".
private val Context.sceneDataStore: DataStore<Preferences> by preferencesDataStore(name = "scene_prefs")

/**
 * Persistence for [Scene]s. Scenes hold no credentials, so plain DataStore is right here —
 * unlike destinations, which carry stream keys and live in encrypted storage.
 */
class SceneStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Load scenes, seeding the default set on first run so the feature is usable immediately. */
    suspend fun load(): List<Scene> {
        val raw = try {
            context.sceneDataStore.data.first()[KEY_SCENES]
        } catch (t: Throwable) {
            Log.e(TAG, "Reading scenes failed", t)
            null
        }
        if (raw.isNullOrBlank()) {
            val defaults = Scene.defaults()
            save(defaults)
            return defaults
        }
        return try {
            json.decodeFromString<List<Scene>>(raw).sortedBy { it.order }
        } catch (t: Throwable) {
            Log.e(TAG, "Stored scenes couldn't be parsed; falling back to defaults", t)
            Scene.defaults()
        }
    }

    suspend fun save(scenes: List<Scene>) {
        val encoded = try {
            json.encodeToString(scenes.sortedBy { it.order })
        } catch (t: Throwable) {
            Log.e(TAG, "Encoding scenes failed", t)
            return
        }
        try {
            context.sceneDataStore.edit { it[KEY_SCENES] = encoded }
        } catch (t: Throwable) {
            Log.e(TAG, "Saving scenes failed", t)
        }
    }

    suspend fun upsert(scene: Scene) {
        val current = load().toMutableList()
        val index = current.indexOfFirst { it.id == scene.id }
        if (index >= 0) current[index] = scene else current.add(scene)
        save(current)
    }

    suspend fun remove(id: String) = save(load().filterNot { it.id == id })

    /** Id of the scene that was active last, so the studio comes back where it left off. */
    suspend fun lastActiveId(): String? = try {
        context.sceneDataStore.data.first()[KEY_ACTIVE]
    } catch (t: Throwable) {
        Log.w(TAG, "Reading active scene failed", t)
        null
    }

    suspend fun setLastActiveId(id: String?) {
        try {
            context.sceneDataStore.edit { prefs ->
                if (id == null) prefs.remove(KEY_ACTIVE) else prefs[KEY_ACTIVE] = id
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Saving active scene failed", t)
        }
    }

    private companion object {
        const val TAG = "SceneStore"
        val KEY_SCENES = stringPreferencesKey("scenes_json")
        val KEY_ACTIVE = stringPreferencesKey("active_scene_id")
    }
}
