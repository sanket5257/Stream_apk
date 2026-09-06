package com.streamforge.app.packs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Graphics Packs — the product layer that sits on top of raw overlays.
 *
 * A plain overlay is "drag a PNG here and type some text". A pack is a designed, data-driven
 * broadcast graphic: a cricket scoreboard, a lower third, a news ticker. The user fills in
 * FIELDS (team names, a presenter's title) and drives them live through ACTIONS ("+4", "W"),
 * while the ELEMENTS describe how to draw it. That separation is the whole point — it is what
 * lets a streamer add a run without leaving the live screen, and what makes a new graphic a
 * content update rather than an app release.
 *
 * Packs are declared as JSON in `assets/packs/`, so adding one ships no Kotlin. See
 * [PackCatalog] for loading and [PackRasterizer] for drawing.
 */
@Serializable
data class GraphicsPack(
    val id: String,
    val name: String,
    val category: PackCategory,
    val description: String = "",
    /** Minimum subscription tier required to broadcast this pack. */
    val tier: PackTier = PackTier.FREE,
    val canvas: PackCanvas,
    /**
     * Default width as a fraction of the frame, 0..1. The renderer's base overlay width is
     * 20% of the frame at scale 1.0, so `scale = defaultWidth / 0.2`.
     */
    val defaultWidth: Float = 0.6f,
    /** Where the pack lands the first time it's added. */
    val anchor: PackAnchor = PackAnchor.BOTTOM_LEFT,
    val fields: List<PackField> = emptyList(),
    val actions: List<PackAction> = emptyList(),
    val elements: List<PackElement> = emptyList(),
) {
    /** Field defaults as the initial value map for a new instance. */
    fun defaultValues(): Map<String, String> = fields.associate { it.key to it.default }
}

@Serializable
data class PackCanvas(val w: Int, val h: Int) {
    val aspect: Float get() = if (h > 0) w.toFloat() / h else 1f
}

@Serializable
enum class PackCategory {
    SPORTS, NEWS, EVENT, WEDDING, GENERAL;

    val displayName: String
        get() = when (this) {
            SPORTS -> "Sports"
            NEWS -> "News"
            EVENT -> "Events"
            WEDDING -> "Wedding"
            GENERAL -> "General"
        }
}

@Serializable
enum class PackTier { FREE, PRO, STUDIO }

@Serializable
enum class PackAnchor {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT;

    /** Centre point as 0..1 fractions of the frame, given the pack's on-frame size. */
    fun centerFor(widthFraction: Float, heightFraction: Float): Pair<Float, Float> {
        val marginX = 0.03f
        val marginY = 0.05f
        val halfW = widthFraction / 2f
        val halfH = heightFraction / 2f
        val x = when (this) {
            TOP_LEFT, BOTTOM_LEFT -> marginX + halfW
            TOP_RIGHT, BOTTOM_RIGHT -> 1f - marginX - halfW
            else -> 0.5f
        }
        val y = when (this) {
            TOP_LEFT, TOP_CENTER, TOP_RIGHT -> marginY + halfH
            BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> 1f - marginY - halfH
            CENTER -> 0.5f
        }
        return x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
    }
}

// ---------------------------------------------------------------------------------------
// Fields — what the user fills in
// ---------------------------------------------------------------------------------------

@Serializable
enum class PackFieldType {
    /** Free text (team name, presenter name). */
    TEXT,

    /** Whole number driven by +/- actions (runs, wickets, goals). */
    NUMBER,

    /** Multi-line text; the ticker cycles through the lines. */
    LINES,

    /** A content:// image URI picked from the gallery (channel logo, team crest). */
    IMAGE,

    /** On/off, used by `showIf` to reveal optional parts of a graphic. */
    TOGGLE,
}

@Serializable
data class PackField(
    val key: String,
    val label: String,
    val type: PackFieldType = PackFieldType.TEXT,
    val default: String = "",
    /** Character cap for TEXT — graphics have fixed boxes, so long input must be bounded. */
    @SerialName("maxLen") val maxLength: Int = 40,
    val min: Int = 0,
    val max: Int = 9999,
    /** Grouping hint for the editor UI, e.g. "Team A". */
    val group: String = "",
)

// ---------------------------------------------------------------------------------------
// Actions — the live control panel
// ---------------------------------------------------------------------------------------

@Serializable
enum class PackActionOp { ADD, SET, TOGGLE, CYCLE }

/**
 * One button on the live control panel. This is the feature that makes a scoreboard usable:
 * a cricket streamer taps "+4" and the graphic updates on air, without ever leaving the
 * camera screen or opening a settings sheet.
 */
@Serializable
data class PackAction(
    val label: String,
    val field: String,
    val op: PackActionOp = PackActionOp.ADD,
    val amount: Int = 1,
    /** Optional grouping so buttons for Team A and Team B render in separate rows. */
    val group: String = "",
    /** Marks a destructive/reset action so the UI can style it differently. */
    val destructive: Boolean = false,
    /**
     * Follow-up effects applied in order after this one. Real scoring actions are rarely a
     * single edit: an over in cricket advances the over count AND resets the ball count, and
     * a wicket adds a wicket AND a ball. Chaining keeps that one tap for the user.
     */
    val also: List<PackAction> = emptyList(),
) {
    /**
     * Apply this action — and any chained [also] effects — to a value map.
     * [fieldFor] resolves a field key to its declaration, which supplies defaults and clamps.
     */
    fun apply(values: Map<String, String>, fieldFor: (String) -> PackField?): Map<String, String> {
        var out = applyTo(values, fieldFor(field))
        also.forEach { out = it.apply(out, fieldFor) }
        return out
    }

    /** Apply just this action, without its chain. */
    fun applyTo(values: Map<String, String>, field: PackField?): Map<String, String> {
        val current = values[this.field] ?: field?.default.orEmpty()
        val next = when (op) {
            PackActionOp.ADD -> {
                val n = current.toIntOrNull() ?: 0
                (n + amount).coerceIn(field?.min ?: 0, field?.max ?: Int.MAX_VALUE).toString()
            }
            PackActionOp.SET -> amount.toString()
            PackActionOp.TOGGLE -> if (current.equals("true", true)) "false" else "true"
            PackActionOp.CYCLE -> {
                // Advance through the lines of a LINES field — how a news ticker moves on to
                // the next headline.
                val lines = current.lines().filter { it.isNotBlank() }
                if (lines.size <= 1) current else (lines.drop(1) + lines.first()).joinToString("\n")
            }
        }
        return values + (this.field to next)
    }
}

// ---------------------------------------------------------------------------------------
// Elements — how it's drawn
// ---------------------------------------------------------------------------------------

@Serializable
enum class PackElementKind { RECT, TEXT, IMAGE }

@Serializable
enum class PackAlign { LEFT, CENTER, RIGHT }

@Serializable
enum class PackWeight { NORMAL, BOLD }

/**
 * One drawing instruction, in the pack's own canvas coordinates (see [PackCanvas]).
 *
 * Coordinates are canvas pixels rather than fractions so a pack reads like a design: "the
 * team box is 260 wide starting at 0". The rasterizer scales the whole canvas to whatever
 * resolution the frame needs.
 */
@Serializable
data class PackElement(
    val kind: PackElementKind,
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 0f,
    val h: Float = 0f,
    val radius: Float = 0f,
    /** Colour token (see [PackTheme]) or a literal `#RRGGBB` / `#AARRGGBB`. */
    val fill: String = "SURFACE",
    /** Text content with `{fieldKey}` placeholders, or a literal string. */
    val text: String = "",
    val size: Float = 48f,
    val align: PackAlign = PackAlign.LEFT,
    val weight: PackWeight = PackWeight.NORMAL,
    /** Uppercase the resolved text — broadcast graphics lean on caps for labels. */
    val caps: Boolean = false,
    /** Letter spacing in ems; small positive values make all-caps labels readable. */
    val tracking: Float = 0f,
    /** IMAGE only: the key of an IMAGE field supplying the content:// URI. */
    val imageField: String = "",
    /**
     * Draw only when this field is non-blank and not "false". Lets one pack cover the
     * "with subtitle" and "without subtitle" cases instead of shipping two.
     */
    val showIf: String = "",
    /** Fit long text by shrinking rather than clipping, down to this fraction of [size]. */
    val minScale: Float = 0.5f,
)

// ---------------------------------------------------------------------------------------
// Theme — the user-swappable colourway
// ---------------------------------------------------------------------------------------

/**
 * A pack's colourway. Packs reference colours by role (`SURFACE`, `ACCENT`) so the same
 * scoreboard can be recoloured for a different channel without touching its layout.
 */
@Serializable
data class PackTheme(
    val key: String,
    val name: String,
    val surface: String,
    val onSurface: String,
    val accent: String,
    val onAccent: String,
    val muted: String,
) {
    companion object {
        const val DEFAULT_KEY = "broadcast_navy"

        val ALL: List<PackTheme> = listOf(
            PackTheme(
                key = DEFAULT_KEY, name = "Broadcast Navy",
                surface = "#F00E1B33", onSurface = "#FFFFFFFF",
                accent = "#FF2ED68C", onAccent = "#FF06371F", muted = "#B3C9D4E3",
            ),
            PackTheme(
                key = "stadium_green", name = "Stadium Green",
                surface = "#F00B3D2A", onSurface = "#FFFFFFFF",
                accent = "#FFFFC93D", onAccent = "#FF2B1D00", muted = "#B3BFE6D2",
            ),
            PackTheme(
                key = "news_red", name = "News Red",
                surface = "#F0141414", onSurface = "#FFFFFFFF",
                accent = "#FFE01B24", onAccent = "#FFFFFFFF", muted = "#B3D0D0D0",
            ),
            PackTheme(
                key = "elegant_gold", name = "Elegant Gold",
                surface = "#EB1A1410", onSurface = "#FFF6EEDC",
                accent = "#FFD4AF37", onAccent = "#FF241B04", muted = "#B3CBBDA0",
            ),
            PackTheme(
                key = "clean_light", name = "Clean Light",
                surface = "#F2FFFFFF", onSurface = "#FF10131A",
                accent = "#FF065FD4", onAccent = "#FFFFFFFF", muted = "#B35F6368",
            ),
        )

        fun byKey(key: String?): PackTheme = ALL.firstOrNull { it.key == key } ?: ALL.first()
    }
}
