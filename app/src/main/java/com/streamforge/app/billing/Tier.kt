package com.streamforge.app.billing

import com.streamforge.app.packs.PackTier

/**
 * Subscription tiers.
 *
 * The limits here are the product. A free tier that streams is what gets the app installed and
 * tried at all; the paid tiers exist because the graphics packs, extra destinations and a clean
 * 1080p frame are what a working sports or event streamer actually needs. Every gate is
 * deliberately about capability, never about crippling reliability — the stream itself never
 * degrades to make a point.
 *
 * DISTRIBUTION: this app ships as a direct APK, not through Play, and there is no payment
 * gateway. A tier is granted by a LICENSE CODE that you issue by hand after taking payment
 * however you like (UPI, bank transfer, cash). See [LicenseManager] and
 * `LICENSE_SYSTEM_SETUP.sql`.
 */
enum class Tier {
    FREE, PRO, STUDIO;

    val displayName: String
        get() = when (this) {
            FREE -> "Free"
            PRO -> "Pro"
            STUDIO -> "Studio"
        }

    val tagline: String
        get() = when (this) {
            FREE -> "Try it out — 720p with a watermark"
            PRO -> "For regular streamers who need it to look right"
            STUDIO -> "For multi-platform and multi-camera work"
        }

    /**
     * Suggested price, shown on the upgrade screen. Plain strings because you set and collect
     * these yourself — edit them here when you change what you charge.
     */
    val priceLabel: String
        get() = when (this) {
            FREE -> "Free"
            PRO -> "₹499 / month"
            STUDIO -> "₹999 / month"
        }

    /** Simultaneous publish destinations. Multistream is the headline Studio feature. */
    val maxDestinations: Int
        get() = when (this) {
            FREE -> 1
            PRO -> 1
            STUDIO -> 3
        }

    /** Saved scenes. One means "no scene switching", which is the honest free experience. */
    val maxScenes: Int
        get() = when (this) {
            FREE -> 1
            PRO -> Int.MAX_VALUE
            STUDIO -> Int.MAX_VALUE
        }

    /** Output height ceiling. 720p is perfectly watchable; 1080p is what a client expects. */
    val maxOutputHeight: Int
        get() = when (this) {
            FREE -> 720
            PRO -> 1080
            STUDIO -> 1080
        }

    /** Whether a StreamForge watermark is burned into the free tier's output. */
    val hasWatermark: Boolean get() = this == FREE

    /** Local recording alongside the live stream. */
    val canRecordLocally: Boolean get() = this != FREE

    /** Highest pack tier this licence may broadcast. */
    val maxPackTier: PackTier
        get() = when (this) {
            FREE -> PackTier.FREE
            PRO -> PackTier.PRO
            STUDIO -> PackTier.STUDIO
        }

    fun allows(packTier: PackTier): Boolean = packTier.ordinal <= maxPackTier.ordinal

    /** Tier ordering, so "is at least Pro" reads clearly at call sites. */
    fun atLeast(other: Tier): Boolean = ordinal >= other.ordinal

    /** The selling points shown on the upgrade screen, in the order they matter. */
    val features: List<String>
        get() = when (this) {
            FREE -> listOf(
                "Stream to one destination",
                "720p output",
                "Free graphics: lower third and logo bug",
                "StreamForge watermark on the stream",
            )
            PRO -> listOf(
                "1080p output, no watermark",
                "Every graphics pack — cricket, kabaddi, football, ticker, wedding",
                "Live scoring controls on the camera screen",
                "Unlimited scenes",
                "Record locally while you stream",
            )
            STUDIO -> listOf(
                "Everything in Pro",
                "Stream to 3 destinations at once (YouTube + Facebook + custom RTMP)",
                "Priority support",
            )
        }

    companion object {
        val purchasable: List<Tier> = listOf(PRO, STUDIO)

        fun fromServerValue(value: String?): Tier =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: FREE
    }
}
