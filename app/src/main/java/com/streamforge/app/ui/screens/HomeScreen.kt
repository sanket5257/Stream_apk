package com.streamforge.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamforge.app.billing.Tier
import com.streamforge.app.overlay.OverlayItem
import com.streamforge.app.scenes.Scene
import com.streamforge.app.stream.Destination
import com.streamforge.app.ui.components.SfBadge
import com.streamforge.app.ui.components.SfCard
import com.streamforge.app.ui.components.SfDivider
import com.streamforge.app.ui.components.SfRow
import com.streamforge.app.ui.components.SfSectionHeader
import com.streamforge.app.ui.theme.SfTheme

/**
 * The launchpad.
 *
 * Everything here answers one question — "am I ready to go live?" — so each row states the
 * thing the user most needs to know (where it's going, what graphics are loaded, at what
 * quality) rather than just naming a settings screen.
 */
@Composable
fun HomeScreen(
    destinations: List<Destination>,
    overlays: List<OverlayItem>,
    scenes: List<Scene>,
    tier: Tier,
    resolutionLabel: String,
    onGoLive: () -> Unit,
    onDestinations: () -> Unit,
    onGraphics: () -> Unit,
    onScenes: () -> Unit,
    onQuality: () -> Unit,
    onProfile: () -> Unit,
    onUpgrade: () -> Unit,
) {
    val active = destinations.filter { it.enabled && it.isConfigured }
    val readyToStream = active.isNotEmpty()
    val packCount = overlays.count { it is OverlayItem.Pack }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Header(tier = tier, onProfile = onProfile)

            GoLiveCard(
                readyToStream = readyToStream,
                destinationSummary = when {
                    active.isEmpty() -> "No destination set up yet"
                    active.size == 1 -> "Streaming to ${active.first().label}"
                    else -> "Streaming to ${active.joinToString { it.label }}"
                },
                onGoLive = onGoLive,
                onSetUp = onDestinations,
            )

            SfSectionHeader("Broadcast")
            SfCard {
                SfRow(
                    icon = Icons.Filled.Podcasts,
                    title = "Destinations",
                    subtitle = when {
                        active.isEmpty() -> "Add your YouTube or Facebook stream key"
                        active.size == 1 -> active.first().label
                        else -> "${active.size} destinations · ${active.joinToString { it.label }}"
                    },
                    onClick = onDestinations,
                )
                SfDivider()
                SfRow(
                    icon = Icons.Filled.HighQuality,
                    title = "Video quality",
                    subtitle = resolutionLabel,
                    onClick = onQuality,
                )
            }

            SfSectionHeader("Your show")
            SfCard {
                SfRow(
                    icon = Icons.Filled.Dashboard,
                    title = "Graphics",
                    subtitle = if (packCount == 0) "Add a scoreboard, lower third or ticker"
                    else "$packCount graphic${if (packCount == 1) "" else "s"} in your show",
                    onClick = onGraphics,
                )
                SfDivider()
                SfRow(
                    icon = Icons.Filled.Theaters,
                    title = "Scenes",
                    subtitle = if (scenes.size <= 1) "Switch between pre-show, live and break layouts"
                    else "${scenes.size} scenes",
                    onClick = onScenes,
                    trailing = if (tier == Tier.FREE) {
                        { SfBadge("PRO", color = MaterialTheme.colorScheme.tertiary) }
                    } else null,
                )
            }

            if (tier == Tier.FREE) {
                SfSectionHeader("Plan")
                SfCard {
                    SfRow(
                        icon = Icons.Filled.WorkspacePremium,
                        title = "Upgrade to Pro",
                        subtitle = "1080p, no watermark, every graphics pack, unlimited scenes",
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        onClick = onUpgrade,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun Header(tier: Tier, onProfile: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "StreamForge",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (tier == Tier.FREE) "Free plan" else "${tier.displayName} plan",
                style = MaterialTheme.typography.bodySmall,
                color = SfTheme.semantic.textTertiary,
            )
        }
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .clickable(onClick = onProfile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.AccountCircle,
                contentDescription = "Account",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * The primary action. When nothing is configured this becomes a setup prompt instead of a
 * button that would fail — a dead Go Live is the fastest way to lose a first-time user.
 */
@Composable
private fun GoLiveCard(
    readyToStream: Boolean,
    destinationSummary: String,
    onGoLive: () -> Unit,
    onSetUp: () -> Unit,
) {
    SfCard(Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                    tint = if (readyToStream) SfTheme.semantic.live else SfTheme.semantic.textTertiary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = destinationSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = if (readyToStream) onGoLive else onSetUp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (readyToStream) SfTheme.semantic.live
                    else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = if (readyToStream) "Go Live" else "Set up a destination",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (readyToStream) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Opens the studio — you'll still tap Go Live there",
                        style = MaterialTheme.typography.bodySmall,
                        color = SfTheme.semantic.textTertiary,
                    )
                }
            }
        }
    }
}
