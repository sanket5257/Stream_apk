package com.streamforge.app.overlay

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Top-level delegate: DataStore must be a process-wide singleton per file.
private val Context.overlayDataStore: DataStore<Preferences> by preferencesDataStore(name = "overlay_prefs")

/**
 * Phase 4B: Persistent storage for overlay items.
 * Uses DataStore with JSON serialization.
 */
class OverlayStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    companion object {
        private val KEY_OVERLAYS = stringPreferencesKey("overlays_json")
    }

    /**
     * Load all saved overlays.
     * Returns empty list if no overlays exist.
     */
    suspend fun loadOverlays(): List<OverlayItem> {
        val preferences = context.overlayDataStore.data.first()
        val jsonString = preferences[KEY_OVERLAYS] ?: return emptyList()
        
        return try {
            json.decodeFromString<List<OverlayItem>>(jsonString)
        } catch (e: Exception) {
            // If deserialization fails, return empty list
            emptyList()
        }
    }

    /**
     * Save all overlays.
     */
    suspend fun saveOverlays(overlays: List<OverlayItem>) {
        val jsonString = json.encodeToString(overlays)
        context.overlayDataStore.edit { preferences ->
            preferences[KEY_OVERLAYS] = jsonString
        }
    }

    /**
     * Add a new overlay.
     */
    suspend fun addOverlay(overlay: OverlayItem) {
        val current = loadOverlays().toMutableList()
        current.add(overlay)
        saveOverlays(current)
    }

    /**
     * Update an existing overlay.
     */
    suspend fun updateOverlay(overlay: OverlayItem) {
        val current = loadOverlays().toMutableList()
        val index = current.indexOfFirst { it.id == overlay.id }
        if (index >= 0) {
            current[index] = overlay
            saveOverlays(current)
        }
    }

    /**
     * Remove an overlay by ID.
     */
    suspend fun removeOverlay(id: String) {
        val current = loadOverlays().toMutableList()
        current.removeAll { it.id == id }
        saveOverlays(current)
    }

    /**
     * Clear all overlays.
     */
    suspend fun clearAll() {
        context.overlayDataStore.edit { preferences ->
            preferences.remove(KEY_OVERLAYS)
        }
    }
}
