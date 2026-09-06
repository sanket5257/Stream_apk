package com.streamforge.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamforge.app.billing.Tier
import com.streamforge.app.storage.StreamConfig
import com.streamforge.app.ui.components.SfBadge
import com.streamforge.app.ui.components.SfCard
import com.streamforge.app.ui.components.SfSectionHeader
import com.streamforge.app.ui.theme.SfTheme
import kotlin.math.roundToInt

/** Preset output sizes. 4:3 and vertical are deliberately absent — YouTube and Facebook
 *  live both expect 16:9, and offering shapes that get letterboxed helps nobody. */
private data class Resolution(val label: String, val width: Int, val height: Int, val suggestedKbps: Int)

private val RESOLUTIONS = listOf(
    Resolution("480p", 854, 480, 1800),
    Resolution("720p", 1280, 720, 3500),
    Resolution("1080p", 1920, 1080, 6000),
)

/**
 * Encoder settings.
 *
 * Every control shows what it means in practice rather than just a number: a bitrate slider
 * that says "good for most 4G uplinks" is far more useful to a streamer at a ground than one
 * that says "4500".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityScreen(
    config: StreamConfig,
    tier: Tier,
    activeDestinationCount: Int,
    onBack: () -> Unit,
    onSave: (StreamConfig) -> Unit,
    onUpgrade: () -> Unit,
) {
    var width by remember { mutableStateOf(config.width) }
    var height by remember { mutableStateOf(config.height) }
    var fps by remember { mutableStateOf(config.fps) }
    var videoKbps by remember { mutableStateOf(config.videoBitrateKbps) }
    var audioKbps by remember { mutableStateOf(config.audioBitrateKbps) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video quality") },
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
            SfSectionHeader("Resolution")
            SfCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    RESOLUTIONS.forEach { resolution ->
                        val locked = resolution.height > tier.maxOutputHeight
                        FilterChip(
                            selected = height == resolution.height,
                            onClick = {
                                if (locked) {
                                    onUpgrade()
                                } else {
                                    width = resolution.width
                                    height = resolution.height
                                    videoKbps = resolution.suggestedKbps
                                }
                            },
                            label = { Text(resolution.label) },
                            trailingIcon = if (locked) {
                                { SfBadge("PRO", color = MaterialTheme.colorScheme.tertiary) }
                            } else null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            SfSectionHeader("Frame rate")
            SfCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    listOf(24, 30, 60).forEach { option ->
                        FilterChip(
                            selected = fps == option,
                            onClick = { fps = option },
                            label = { Text("$option fps") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            SfSectionHeader("Video bitrate")
            SfCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${videoKbps} kbps",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            bitrateAdvice(videoKbps),
                            style = MaterialTheme.typography.bodySmall,
                            color = SfTheme.semantic.textTertiary,
                        )
                    }
                    Slider(
                        value = videoKbps.toFloat(),
                        onValueChange = { videoKbps = (it / 250).roundToInt() * 250 },
                        valueRange = 1000f..9000f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (activeDestinationCount > 1) {
                        Text(
                            text = "You're streaming to $activeDestinationCount destinations. " +
                                "They share one encode, so this is automatically eased back at " +
                                "Go Live to fit your upload speed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SfTheme.semantic.warning,
                        )
                    } else {
                        Text(
                            "Bitrate is adjusted automatically while you stream if your " +
                                "connection can't keep up.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SfTheme.semantic.textTertiary,
                        )
                    }
                }
            }

            SfSectionHeader("Audio bitrate")
            SfCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "${audioKbps} kbps",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Slider(
                        value = audioKbps.toFloat(),
                        onValueChange = { audioKbps = (it / 32).roundToInt() * 32 },
                        valueRange = 64f..256f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "128 kbps is plenty for speech and crowd noise.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SfTheme.semantic.textTertiary,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onSave(
                        config.copy(
                            width = width,
                            height = height,
                            fps = fps,
                            videoBitrateKbps = videoKbps,
                            audioBitrateKbps = audioKbps,
                        )
                    )
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(52.dp),
                shape = CircleShape,
            ) { Text("Save", fontWeight = FontWeight.SemiBold) }

            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun bitrateAdvice(kbps: Int): String = when {
    kbps < 2000 -> "Safe on weak 4G"
    kbps < 4000 -> "Good for most 4G uplinks"
    kbps < 6500 -> "Needs a strong connection"
    else -> "Wi-Fi or excellent 5G only"
}
