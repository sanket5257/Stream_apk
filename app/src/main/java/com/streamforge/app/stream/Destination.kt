package com.streamforge.app.stream

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A place the broadcast is published to.
 *
 * Multistream works by encoding ONCE and fanning the same H.264/AAC frames out to several RTMP
 * clients (RootEncoder's MultiRtpCamera2). There is no extra encode per destination, so the
 * cost of a second destination is uplink bandwidth, not CPU or battery — which is why this is
 * viable on a phone at all.
 */
@Serializable
enum class Platform {
    YOUTUBE,
    FACEBOOK,
    INSTAGRAM,
    CUSTOM;

    val displayName: String
        get() = when (this) {
            YOUTUBE -> "YouTube"
            FACEBOOK -> "Facebook"
            INSTAGRAM -> "Instagram"
            CUSTOM -> "Custom RTMP"
        }

    /** Default ingest endpoint. The user's stream key is appended to this. */
    val defaultIngestUrl: String
        get() = when (this) {
            YOUTUBE -> "rtmp://a.rtmp.youtube.com/live2/"
            FACEBOOK -> "rtmps://live-api-s.facebook.com:443/rtmp/"
            INSTAGRAM -> ""
            CUSTOM -> ""
        }

    /**
     * Instagram has no public RTMP ingest: Live Producer was retired for most accounts, and
     * the endpoints that still exist need a key minted per-broadcast through a Graph API flow
     * we don't have yet. Shipping a box that silently fails would be worse than saying so, so
     * the destination is listed and visibly marked "Coming soon" rather than hidden — people
     * ask for it, and seeing it on the roadmap answers the question.
     */
    val isAvailable: Boolean get() = this != INSTAGRAM

    val comingSoonNote: String?
        get() = when (this) {
            INSTAGRAM -> "Instagram doesn't offer an open RTMP endpoint yet. " +
                "We'll switch this on as soon as their API allows it."
            else -> null
        }

    /**
     * Whether the endpoint is RTMPS (TLS). Facebook requires it. RootEncoder's RtmpClient
     * handles the rtmps:// scheme itself; this is only used for the UI hint.
     */
    val requiresTls: Boolean get() = this == FACEBOOK
}

@Serializable
data class Destination(
    val id: String = UUID.randomUUID().toString(),
    val platform: Platform,
    /** User-facing name. Defaults to the platform name; editable for multiple custom targets. */
    val label: String = platform.displayName,
    /** Ingest base URL. Empty means "use the platform default". */
    val ingestUrl: String = "",
    val streamKey: String = "",
    /** Whether this destination is included in the next broadcast. */
    val enabled: Boolean = true,
) {
    val effectiveIngestUrl: String
        get() = ingestUrl.ifBlank { platform.defaultIngestUrl }

    /** True when this destination has everything it needs to be published to. */
    val isConfigured: Boolean
        get() = platform.isAvailable && streamKey.isNotBlank() && effectiveIngestUrl.isNotBlank()

    /**
     * Full publish URL. RTMP ingest URLs are `<base>/<key>`; bases are stored with or without
     * the trailing slash depending on where they were pasted from, so normalise here rather
     * than at each call site.
     */
    fun publishUrl(): String {
        val base = effectiveIngestUrl.trimEnd('/')
        return "$base/${streamKey.trim()}"
    }

    /** Masked key for display — never render a stream key in full. */
    val maskedKey: String
        get() = if (streamKey.isBlank()) "" else "••••${streamKey.takeLast(4)}"
}

/** Per-destination connection state, so one failing target doesn't hide the others. */
sealed class DestinationState {
    data object Idle : DestinationState()
    data object Connecting : DestinationState()
    data object Live : DestinationState()
    data class Failed(val reason: String) : DestinationState()
}
