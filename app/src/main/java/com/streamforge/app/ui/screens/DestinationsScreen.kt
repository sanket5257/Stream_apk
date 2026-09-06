package com.streamforge.app.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.streamforge.app.billing.Tier
import com.streamforge.app.stream.Destination
import com.streamforge.app.stream.Platform
import com.streamforge.app.ui.components.SfBadge
import com.streamforge.app.ui.components.SfCard
import com.streamforge.app.ui.components.SfDivider
import com.streamforge.app.ui.components.SfEmptyState
import com.streamforge.app.ui.components.SfRow
import com.streamforge.app.ui.components.SfSectionHeader
import com.streamforge.app.ui.studio.brandColor
import com.streamforge.app.ui.theme.SfTheme

/**
 * Where the broadcast goes.
 *
 * Instagram is listed but disabled: it has no open RTMP ingest any more, and the endpoints
 * that survive need a key minted per-broadcast through a Graph API flow this app doesn't have.
 * Showing it greyed out with an explanation is more useful than hiding it — people ask for
 * Instagram constantly, and this answers the question without a support message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationsScreen(
    destinations: List<Destination>,
    tier: Tier,
    onBack: () -> Unit,
    onSave: (Destination) -> Unit,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
    onUpgrade: () -> Unit,
) {
    var editing by remember { mutableStateOf<Destination?>(null) }
    var addingPlatform by remember { mutableStateOf<Platform?>(null) }
    var comingSoon by remember { mutableStateOf<Platform?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Destinations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            val configured = destinations.filter { it.streamKey.isNotBlank() }

            if (configured.isEmpty()) {
                SfEmptyState(
                    icon = Icons.Filled.Podcasts,
                    title = "No destinations yet",
                    message = "Add your YouTube stream key to go live. You'll find it in " +
                        "YouTube Studio → Go Live → Stream settings.",
                )
            } else {
                SfSectionHeader("Your destinations")
                SfCard {
                    configured.forEachIndexed { index, destination ->
                        if (index > 0) SfDivider()
                        DestinationRow(
                            destination = destination,
                            onToggle = { onToggle(destination.id) },
                            onEdit = { editing = destination },
                        )
                    }
                }
                if (tier.maxDestinations == 1 && configured.size > 1) {
                    Text(
                        text = "Only one destination streams at a time on your plan. " +
                            "Studio streams to three at once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SfTheme.semantic.warning,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }

            SfSectionHeader("Add a destination")
            SfCard {
                Platform.entries.forEachIndexed { index, platform ->
                    if (index > 0) SfDivider()
                    SfRow(
                        icon = if (platform.isAvailable) Icons.Filled.Add else Icons.Filled.Lock,
                        title = platform.displayName,
                        subtitle = if (platform.isAvailable) {
                            when (platform) {
                                Platform.YOUTUBE -> "Paste your stream key from YouTube Studio"
                                Platform.FACEBOOK -> "Paste your stream key from Facebook Live Producer"
                                else -> "Any RTMP or RTMPS server"
                            }
                        } else "Coming soon",
                        iconTint = platform.brandColor(),
                        enabled = platform.isAvailable,
                        showChevron = platform.isAvailable,
                        trailing = if (!platform.isAvailable) {
                            { SfBadge("SOON", color = SfTheme.semantic.warning) }
                        } else null,
                        onClick = {
                            if (platform.isAvailable) addingPlatform = platform
                            else comingSoon = platform
                        },
                    )
                }
            }

            if (tier.maxDestinations < 3) {
                SfSectionHeader("Multistream")
                SfCard {
                    SfRow(
                        icon = Icons.Filled.Podcasts,
                        title = "Stream everywhere at once",
                        subtitle = "Studio publishes to YouTube, Facebook and a custom RTMP " +
                            "server simultaneously — one encode, three destinations.",
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        onClick = onUpgrade,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    // --- Dialogs -------------------------------------------------------------------

    addingPlatform?.let { platform ->
        DestinationEditor(
            initial = Destination(platform = platform),
            onDismiss = { addingPlatform = null },
            onSave = { onSave(it); addingPlatform = null },
            onDelete = null,
        )
    }

    editing?.let { destination ->
        DestinationEditor(
            initial = destination,
            onDismiss = { editing = null },
            onSave = { onSave(it); editing = null },
            onDelete = { onDelete(destination.id); editing = null },
        )
    }

    comingSoon?.let { platform ->
        AlertDialog(
            onDismissRequest = { comingSoon = null },
            title = { Text("${platform.displayName} — coming soon") },
            text = { Text(platform.comingSoonNote ?: "Not available yet.") },
            confirmButton = { TextButton(onClick = { comingSoon = null }) { Text("Got it") } },
        )
    }
}

@Composable
private fun DestinationRow(
    destination: Destination,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    SfRow(
        icon = Icons.Filled.Podcasts,
        title = destination.label,
        subtitle = if (destination.streamKey.isBlank()) "No stream key"
        else "Key ${destination.maskedKey}",
        iconTint = destination.platform.brandColor(),
        onClick = onEdit,
        showChevron = false,
        trailing = {
            Switch(checked = destination.enabled, onCheckedChange = { onToggle() })
        },
    )
}

/**
 * Add / edit dialog.
 *
 * The stream key is masked by default. It is a credential — someone can hijack a channel's
 * broadcast with it — and these screens get shown to other people at grounds and events.
 */
@Composable
private fun DestinationEditor(
    initial: Destination,
    onDismiss: () -> Unit,
    onSave: (Destination) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var label by remember { mutableStateOf(initial.label) }
    var key by remember { mutableStateOf(initial.streamKey) }
    var url by remember { mutableStateOf(initial.ingestUrl) }
    var revealKey by remember { mutableStateOf(false) }

    val needsUrl = initial.platform == Platform.CUSTOM

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(initial.platform.displayName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                if (needsUrl) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("RTMP server URL") },
                        placeholder = { Text("rtmp://your-server/live") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text("Stream key") },
                    singleLine = true,
                    visualTransformation = if (revealKey) androidx.compose.ui.text.input.VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions.Default,
                    trailingIcon = {
                        TextButton(onClick = { revealKey = !revealKey }) {
                            Text(if (revealKey) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (initial.platform == Platform.FACEBOOK) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Facebook keys expire. If the stream stops connecting, generate a " +
                            "fresh key in Live Producer and paste it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SfTheme.semantic.textTertiary,
                    )
                }

                if (onDelete != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDelete) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            label = label.ifBlank { initial.platform.displayName },
                            streamKey = key,
                            ingestUrl = url,
                        )
                    )
                },
                enabled = key.isNotBlank() && (!needsUrl || url.isNotBlank()),
                shape = CircleShape,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
