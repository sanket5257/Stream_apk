package com.streamforge.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.SportsCricket
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.streamforge.app.billing.Tier
import com.streamforge.app.overlay.OverlayItem
import com.streamforge.app.packs.GraphicsPack
import com.streamforge.app.packs.PackCategory
import com.streamforge.app.packs.PackTier
import com.streamforge.app.ui.components.SfBadge
import com.streamforge.app.ui.components.SfCard
import com.streamforge.app.ui.components.SfDivider
import com.streamforge.app.ui.components.SfEmptyState
import com.streamforge.app.ui.components.SfRow
import com.streamforge.app.ui.components.SfSectionHeader
import com.streamforge.app.ui.theme.SfTheme

/**
 * Graphics: what's in the show, and what can be added.
 *
 * "Your show" comes first because that is what people return here to edit. The catalogue sits
 * below it, grouped by category so a cricket streamer sees cricket first rather than scrolling
 * a flat list of everything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphicsScreen(
    catalog: List<GraphicsPack>,
    overlays: List<OverlayItem>,
    tier: Tier,
    onBack: () -> Unit,
    onAdd: (GraphicsPack) -> Unit,
    onEdit: (String) -> Unit,
    onRemove: (String) -> Unit,
    onUpgrade: () -> Unit,
) {
    val inShow = overlays.filterIsInstance<OverlayItem.Pack>()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Graphics") },
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
            if (inShow.isEmpty()) {
                SfEmptyState(
                    icon = Icons.Filled.Dashboard,
                    title = "No graphics yet",
                    message = "Add a scoreboard, lower third or headline bar. You'll be able " +
                        "to change the score live from the camera screen.",
                )
            } else {
                SfSectionHeader("In your show")
                SfCard {
                    inShow.forEachIndexed { index, overlay ->
                        if (index > 0) SfDivider()
                        val definition = catalog.firstOrNull { it.id == overlay.packId }
                        SfRow(
                            icon = definition?.category.icon(),
                            title = definition?.name ?: overlay.packId,
                            subtitle = summaryFor(definition, overlay),
                            onClick = { onEdit(overlay.id) },
                        )
                    }
                }
                Text(
                    text = "Position and size are set by dragging the graphic in the studio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SfTheme.semantic.textTertiary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }

            // An empty catalogue renders as an empty screen, which reads as "the feature is
            // broken" with nothing to report. Say what actually happened instead.
            if (catalog.isEmpty()) {
                SfSectionHeader("Add a graphic")
                SfCard {
                    Text(
                        text = "No graphics could be loaded from this build. " +
                            "Profile › Diagnostics has the details.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            val byCategory = catalog.groupBy { it.category }
            PackCategory.entries.forEach { category ->
                val packs = byCategory[category].orEmpty()
                if (packs.isEmpty()) return@forEach

                SfSectionHeader(category.displayName)
                SfCard {
                    packs.forEachIndexed { index, pack ->
                        if (index > 0) SfDivider()
                        val locked = !tier.allows(pack.tier)
                        SfRow(
                            icon = if (locked) Icons.Filled.Lock else Icons.Filled.Add,
                            title = pack.name,
                            subtitle = pack.description,
                            iconTint = if (locked) SfTheme.semantic.textTertiary
                            else MaterialTheme.colorScheme.primary,
                            showChevron = false,
                            trailing = when {
                                locked -> {
                                    { SfBadge(pack.tier.badgeLabel(), color = MaterialTheme.colorScheme.tertiary) }
                                }
                                pack.tier == PackTier.FREE -> {
                                    { SfBadge("FREE", color = SfTheme.semantic.success) }
                                }
                                else -> null
                            },
                            onClick = { if (locked) onUpgrade() else onAdd(pack) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * One-line description of an instance: what it currently says on air.
 *
 * Guarded because this runs during composition, reading a pack definition that came off disk.
 * A malformed or half-written pack would otherwise throw on the way to drawing the row, and a
 * throw in composition is not something a click guard can catch — it takes the screen, and
 * with it the app. A row with a generic subtitle is a far better outcome.
 */
private fun summaryFor(definition: GraphicsPack?, overlay: OverlayItem.Pack): String {
    if (definition == null) return "This graphic is no longer available"
    return runCatching {
        val values = definition.defaultValues() + overlay.values
        // Show the first couple of text-ish fields — for a scoreboard that's the two team names,
        // which is exactly how the user thinks of "which scoreboard is this".
        val preview = definition.fields
            .filter { it.type.name == "TEXT" }
            .mapNotNull { values[it.key]?.takeIf { v -> v.isNotBlank() } }
            .take(2)
            .joinToString(" · ")
        preview.ifBlank { definition.description }
    }.getOrElse {
        android.util.Log.e("GraphicsScreen", "Summarising pack ${overlay.packId} failed", it)
        definition.description
    }
}

private fun PackTier.badgeLabel(): String = when (this) {
    PackTier.FREE -> "FREE"
    PackTier.PRO -> "PRO"
    PackTier.STUDIO -> "STUDIO"
}

private fun PackCategory?.icon(): ImageVector = when (this) {
    PackCategory.SPORTS -> Icons.Filled.SportsCricket
    PackCategory.NEWS -> Icons.Filled.Newspaper
    PackCategory.EVENT -> Icons.Filled.EmojiEvents
    PackCategory.WEDDING -> Icons.Filled.Favorite
    PackCategory.GENERAL -> Icons.Filled.Star
    null -> Icons.Filled.Dashboard
}
