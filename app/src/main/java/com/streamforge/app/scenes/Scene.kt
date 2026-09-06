package com.streamforge.app.scenes

import com.streamforge.app.overlay.OverlayItem
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A saved arrangement of the show: which overlays are on screen, and what their graphics say.
 *
 * Scenes are how an amateur stream starts to look like a channel — a pre-show card, the live
 * layout, a break slate, an outro — switched with one tap and no interruption to the RTMP
 * connection. Switching only changes overlay ALPHA in the GL pipeline (see
 * OverlayRenderer.applySceneVisibility), so nothing is re-encoded, re-uploaded, or reconnected.
 *
 * A scene stores the ids it knows about rather than a full overlay list, so adding a new
 * overlay later doesn't invalidate scenes that were saved before it existed — an id the scene
 * has no opinion on simply keeps whatever it is doing.
 */
@Serializable
data class Scene(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val order: Int = 0,
    /** Overlay id → on screen in this scene. Ids absent from the map are left as they are. */
    val visibility: Map<String, Boolean> = emptyMap(),
    /**
     * Overlay id → graphics-pack field values captured with the scene. Lets a "Half time"
     * scene carry a different scoreboard note from the "Live" scene without extra overlays.
     */
    val packValues: Map<String, Map<String, String>> = emptyMap(),
) {
    /** Overlay ids this scene wants hidden, given the overlays that currently exist. */
    fun hiddenIdsFor(overlays: List<OverlayItem>): Set<String> =
        overlays.filter { visibility[it.id] == false }.map { it.id }.toSet()

    /**
     * Apply this scene's captured pack values to the overlay list. Returns the same list when
     * nothing changed, so callers can skip a pointless re-render.
     */
    fun applyPackValues(overlays: List<OverlayItem>): List<OverlayItem> {
        if (packValues.isEmpty()) return overlays
        var changed = false
        val updated = overlays.map { item ->
            if (item !is OverlayItem.Pack) return@map item
            val saved = packValues[item.id] ?: return@map item
            if (saved == item.values) return@map item
            changed = true
            item.copy(values = saved)
        }
        return if (changed) updated else overlays
    }

    companion object {
        /**
         * The starting set every user gets. These are the four states essentially every
         * broadcast moves between, so having them pre-made is the difference between scenes
         * being a feature people use and a feature people would have to design first.
         */
        fun defaults(): List<Scene> = listOf(
            Scene(name = "Pre-show", order = 0),
            Scene(name = "Live", order = 1),
            Scene(name = "Break", order = 2),
            Scene(name = "Outro", order = 3),
        )

        /** Snapshot the current overlay arrangement into a scene. */
        fun capture(name: String, order: Int, overlays: List<OverlayItem>, hidden: Set<String>): Scene =
            Scene(
                name = name,
                order = order,
                visibility = overlays.associate { it.id to (it.id !in hidden) },
                packValues = overlays.filterIsInstance<OverlayItem.Pack>()
                    .associate { it.id to it.values },
            )
    }
}
