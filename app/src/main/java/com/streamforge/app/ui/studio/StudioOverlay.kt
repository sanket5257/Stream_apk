package com.streamforge.app.ui.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Theaters

import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamforge.app.billing.Tier
import com.streamforge.app.overlay.OverlayItem
import com.streamforge.app.packs.GraphicsPack
import com.streamforge.app.packs.PackAction
import com.streamforge.app.scenes.Scene
import com.streamforge.app.stream.Destination
import com.streamforge.app.stream.DestinationState
import com.streamforge.app.stream.Platform
import com.streamforge.app.stream.StreamState
import com.streamforge.app.ui.components.SfStatusDot
import com.streamforge.app.ui.theme.SfChipOnCamera
import com.streamforge.app.ui.theme.SfCustomPurple
import com.streamforge.app.ui.theme.SfFacebookBlue
import com.streamforge.app.ui.theme.SfInstagramPink
import com.streamforge.app.ui.theme.SfOnCamera
import com.streamforge.app.ui.theme.SfOnCameraMuted
import com.streamforge.app.ui.theme.SfTheme
import com.streamforge.app.ui.theme.SfYouTubeRed

/**
 * The studio HUD — everything the user sees on top of the camera while streaming.
 *
 * This is a Compose layer over RootEncoder's native OpenGlView, NOT a replacement for it. The
 * GL surface and the overlay gesture view stay native Android views because they are the
 * encoder's video feed; only the chrome is Compose. That split is what lets the control
 * surface be modern without touching the streaming path at all.
 */

/** Everything the HUD renders. Held by StreamActivity and pushed in on each change. */
data class StudioUiState(
    val streamState: StreamState = StreamState.Idle,
    val destinations: List<Destination> = emptyList(),
    val destinationStates: Map<String, DestinationState> = emptyMap(),
    val muted: Boolean = false,
    val audioLevel: Int = 0,
    val bitrateKbps: Int = 0,
    val fps: Int = 0,
    val uptime: String = "",
    val scenes: List<Scene> = emptyList(),
    val activeSceneId: String? = null,
    /** Pack overlays currently in the show, paired with their definitions. */
    val packs: List<StudioPack> = emptyList(),
    val openPanel: StudioPanel = StudioPanel.NONE,
    /** Which pack's controls are showing when [openPanel] is CONTROLS. */
    val activePackOverlayId: String? = null,
    val tier: Tier = Tier.FREE,
)

data class StudioPack(val overlay: OverlayItem.Pack, val definition: GraphicsPack)

enum class StudioPanel { NONE, CONTROLS, SCENES }

/** Everything the HUD can ask the activity to do. */
data class StudioCallbacks(
    val onGoLive: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onSwitchCamera: () -> Unit = {},
    val onToggleMute: () -> Unit = {},
    val onRotate: () -> Unit = {},
    val onManageOverlays: () -> Unit = {},
    val onTogglePanel: (StudioPanel) -> Unit = {},
    val onSelectPack: (String) -> Unit = {},
    val onPackAction: (String, PackAction) -> Unit = { _, _ -> },
    val onSelectScene: (String) -> Unit = {},
    val onSaveScene: (String) -> Unit = {},
    val onExit: () -> Unit = {},
)

/**
 * Top chrome: status, stats, destination health, mic level.
 *
 * Hosted in its own wrap_content ComposeView so it never covers — and never steals touches
 * from — the overlay editor in the middle of the frame.
 */
@Composable
fun StudioTopChrome(state: StudioUiState, callbacks: StudioCallbacks) {
    TopBar(state, callbacks, Modifier.fillMaxWidth())
}

/** Bottom chrome: the expandable panels plus the control bar and Go Live. */
@Composable
fun StudioBottomChrome(state: StudioUiState, callbacks: StudioCallbacks) {
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = state.openPanel == StudioPanel.CONTROLS,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            LiveControlPanel(state, callbacks)
        }
        AnimatedVisibility(
            visible = state.openPanel == StudioPanel.SCENES,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            SceneStrip(state, callbacks)
        }
        BottomBar(state, callbacks)
    }
}

// ---------------------------------------------------------------------------------------
// Top bar: status, stats, destinations, mic
// ---------------------------------------------------------------------------------------

@Composable
private fun TopBar(state: StudioUiState, callbacks: StudioCallbacks, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudChip(onClick = callbacks.onExit) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Leave studio",
                tint = SfOnCamera,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))

        StatusPill(state.streamState)

        if (state.streamState is StreamState.Live) {
            Spacer(Modifier.width(8.dp))
            HudChip {
                Text(
                    text = "${state.bitrateKbps} kbps · ${state.fps} fps · ${state.uptime}",
                    color = SfOnCameraMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (state.destinations.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.destinations.forEach { destination ->
                    DestinationChip(destination, state.destinationStates[destination.id])
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        MicMeter(state.muted, state.audioLevel)
    }
}

@Composable
private fun StatusPill(streamState: StreamState) {
    val (label, color) = when (streamState) {
        is StreamState.Idle -> "Ready" to SfOnCameraMuted
        is StreamState.Connecting -> "Connecting…" to SfTheme.semantic.warning
        is StreamState.Live -> "LIVE" to SfTheme.semantic.live
        is StreamState.Failed -> "Failed" to MaterialTheme.colorScheme.error
    }
    HudChip {
        if (streamState is StreamState.Live) {
            Icon(
                Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(10.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun DestinationChip(destination: Destination, state: DestinationState?) {
    val statusColor = when (state) {
        is DestinationState.Live -> SfTheme.semantic.success
        is DestinationState.Connecting -> SfTheme.semantic.warning
        is DestinationState.Failed -> MaterialTheme.colorScheme.error
        else -> SfOnCameraMuted
    }
    HudChip {
        SfStatusDot(destination.platform.brandColor(), size = 8.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = destination.label,
            color = SfOnCamera,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 90.dp),
        )
        Spacer(Modifier.width(6.dp))
        SfStatusDot(statusColor, size = 6.dp)
    }
}

@Composable
private fun MicMeter(muted: Boolean, level: Int) {
    Surface(
        color = SfChipOnCamera,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .width(120.dp)
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                text = if (muted) "MIC MUTED" else "MIC",
                color = if (muted) MaterialTheme.colorScheme.error else SfOnCameraMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (muted) 0f else (level / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = SfTheme.semantic.success,
                trackColor = Color.White.copy(alpha = 0.22f),
                drawStopIndicator = {},
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Live control panel — the reason a scoreboard is worth paying for
// ---------------------------------------------------------------------------------------

/**
 * The scoring controls, shown over the live preview.
 *
 * This exists because a cricket streamer cannot leave the camera screen to add a run. Every
 * button here writes straight into the on-air graphic. If this panel is slow, fiddly, or
 * hidden behind a menu, the whole graphics-pack feature is worthless.
 */
@Composable
private fun LiveControlPanel(state: StudioUiState, callbacks: StudioCallbacks) {
    val active = state.packs.firstOrNull { it.overlay.id == state.activePackOverlayId }
        ?: state.packs.firstOrNull()

    Surface(
        color = SfChipOnCamera,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (state.packs.isEmpty()) {
                Text(
                    text = "No graphics added yet. Add a scoreboard or lower third from " +
                        "Graphics on the home screen.",
                    color = SfOnCameraMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                return@Column
            }

            // Pack selector — only when there is more than one, so the common case stays clean.
            if (state.packs.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.packs.forEach { pack ->
                        val selected = pack.overlay.id == active?.overlay.let { it?.id }
                        SelectChip(
                            label = pack.definition.name,
                            selected = selected,
                            onClick = { callbacks.onSelectPack(pack.overlay.id) },
                        )
                    }
                }
            }

            val definition = active?.definition
            if (definition == null || definition.actions.isEmpty()) {
                Text(
                    text = definition?.let { "${it.name} has no live controls — edit its text from Graphics." }
                        ?: "Select a graphic.",
                    color = SfOnCameraMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                return@Column
            }

            // Group buttons so Team A and Team B never sit in one undifferentiated row —
            // tapping the wrong team's score on air is the mistake this prevents.
            val groups = definition.actions.groupBy { it.group }
            Column(
                modifier = Modifier.heightIn(max = 180.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { (group, actions) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (group.isNotBlank()) {
                            Text(
                                text = group,
                                color = SfOnCameraMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(64.dp),
                            )
                        }
                        actions.forEach { action ->
                            ActionButton(action) {
                                active.overlay.id.let { callbacks.onPackAction(it, action) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(action: PackAction, onClick: () -> Unit) {
    val tint = if (action.destructive) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    Surface(
        color = tint.copy(alpha = 0.18f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = action.label,
            color = SfOnCamera,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            // Generous padding: these are tapped fast, one-handed, often in sunlight.
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------
// Scenes
// ---------------------------------------------------------------------------------------

@Composable
private fun SceneStrip(state: StudioUiState, callbacks: StudioCallbacks) {
    Surface(
        color = SfChipOnCamera,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = "SCENES",
                color = SfOnCameraMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.scenes.forEach { scene ->
                    SelectChip(
                        label = scene.name,
                        selected = scene.id == state.activeSceneId,
                        onClick = { callbacks.onSelectScene(scene.id) },
                        onLongClick = { callbacks.onSaveScene(scene.id) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Tap to switch · long-press to save the current layout into a scene",
                color = SfOnCameraMuted,
                fontSize = 11.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Bottom bar
// ---------------------------------------------------------------------------------------

@Composable
private fun BottomBar(state: StudioUiState, callbacks: StudioCallbacks) {
    val isLive = state.streamState is StreamState.Live || state.streamState is StreamState.Connecting

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                )
            )
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HudIconButton(Icons.Filled.Layers, "Overlays", callbacks.onManageOverlays)
        HudIconButton(
            icon = Icons.Filled.Dashboard,
            label = "Live controls",
            active = state.openPanel == StudioPanel.CONTROLS,
            onClick = { callbacks.onTogglePanel(StudioPanel.CONTROLS) },
        )
        HudIconButton(
            icon = Icons.Filled.Theaters,
            label = "Scenes",
            active = state.openPanel == StudioPanel.SCENES,
            onClick = { callbacks.onTogglePanel(StudioPanel.SCENES) },
        )
        HudIconButton(
            icon = if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
            label = if (state.muted) "Unmute" else "Mute",
            tint = if (state.muted) MaterialTheme.colorScheme.error else SfOnCamera,
            onClick = callbacks.onToggleMute,
        )
        HudIconButton(Icons.Filled.Cameraswitch, "Switch camera", callbacks.onSwitchCamera)
        HudIconButton(Icons.Filled.ScreenRotation, "Rotate", callbacks.onRotate)

        Spacer(Modifier.weight(1f))

        GoLiveButton(isLive = isLive, onClick = if (isLive) callbacks.onStop else callbacks.onGoLive)
    }
}

@Composable
private fun GoLiveButton(isLive: Boolean, onClick: () -> Unit) {
    val container = if (isLive) Color.White.copy(alpha = 0.18f) else SfTheme.semantic.live
    Surface(
        color = container,
        shape = CircleShape,
        modifier = Modifier
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isLive) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = SfOnCamera,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (isLive) "Stop" else "Go Live",
                color = SfOnCamera,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------------------

@Composable
private fun HudChip(
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Surface(
        color = SfChipOnCamera,
        shape = CircleShape,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun HudIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    tint: Color = SfOnCamera,
) {
    val background = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    else SfChipOnCamera
    Surface(
        color = background,
        shape = CircleShape,
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val container = if (selected) MaterialTheme.colorScheme.primary
    else Color.White.copy(alpha = 0.14f)
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else SfOnCamera
    Surface(
        color = container,
        shape = CircleShape,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Text(
            text = label,
            color = content,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

/** Brand accent for a destination chip. Small dots only — never a large branded fill. */
fun Platform.brandColor(): Color = when (this) {
    Platform.YOUTUBE -> SfYouTubeRed
    Platform.FACEBOOK -> SfFacebookBlue
    Platform.INSTAGRAM -> SfInstagramPink
    Platform.CUSTOM -> SfCustomPurple
}

