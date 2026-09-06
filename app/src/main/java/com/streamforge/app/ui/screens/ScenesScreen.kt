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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.streamforge.app.billing.Tier
import com.streamforge.app.overlay.OverlayItem
import com.streamforge.app.packs.GraphicsPack
import com.streamforge.app.scenes.Scene
import com.streamforge.app.ui.components.SfCard
import com.streamforge.app.ui.components.SfDivider
import com.streamforge.app.ui.components.SfEmptyState
import com.streamforge.app.ui.components.SfSectionHeader
import com.streamforge.app.ui.theme.SfTheme

/**
 * Scenes: named layouts you switch between mid-broadcast.
 *
 * Each scene is just "which overlays are on screen". Switching only changes overlay opacity in
 * the GL pipeline, so it is instant and never interrupts the stream — which is why a scene
 * change is safe to do on air, and why this is worth setting up in advance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenesScreen(
    scenes: List<Scene>,
    overlays: List<OverlayItem>,
    catalog: List<GraphicsPack>,
    tier: Tier,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onSetVisibility: (String, String, Boolean) -> Unit,
    onUpgrade: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Scene?>(null) }
    var adding by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(scenes.firstOrNull()?.id) }

    val canAddMore = scenes.size < tier.maxScenes

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scenes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (canAddMore) adding = true else onUpgrade() }) {
                Icon(Icons.Filled.Add, contentDescription = "Add scene")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (overlays.isEmpty()) {
                SfEmptyState(
                    icon = Icons.Filled.Theaters,
                    title = "Add some graphics first",
                    message = "A scene decides which of your overlays are on screen. " +
                        "Once you've added a graphic or two, come back and arrange them here.",
                )
                return@Column
            }

            Text(
                text = "Tick what each scene shows. Switch between them from the studio — " +
                    "it takes effect instantly, even while you're live.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )

            scenes.forEach { scene ->
                SceneCard(
                    scene = scene,
                    overlays = overlays,
                    catalog = catalog,
                    expanded = expandedId == scene.id,
                    canDelete = scenes.size > 1,
                    onToggleExpanded = { expandedId = if (expandedId == scene.id) null else scene.id },
                    onRename = { renaming = scene },
                    onRemove = { onRemove(scene.id) },
                    onSetVisibility = { overlayId, visible ->
                        onSetVisibility(scene.id, overlayId, visible)
                    },
                )
            }

            if (!canAddMore) {
                Text(
                    text = "More than one scene needs Pro.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SfTheme.semantic.warning,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(90.dp))
        }
    }

    if (adding) {
        NameDialog(
            title = "New scene",
            initial = "",
            onDismiss = { adding = false },
            onConfirm = { onAdd(it); adding = false },
        )
    }

    renaming?.let { scene ->
        NameDialog(
            title = "Rename scene",
            initial = scene.name,
            onDismiss = { renaming = null },
            onConfirm = { onRename(scene.id, it); renaming = null },
        )
    }
}

@Composable
private fun SceneCard(
    scene: Scene,
    overlays: List<OverlayItem>,
    catalog: List<GraphicsPack>,
    expanded: Boolean,
    canDelete: Boolean,
    onToggleExpanded: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onSetVisibility: (String, Boolean) -> Unit,
) {
    val shownCount = overlays.count { scene.visibility[it.id] != false }

    SfSectionHeader(scene.name)
    SfCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "$shownCount of ${overlays.size} shown",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Filled.Edit, contentDescription = "Rename", modifier = Modifier.size(20.dp))
            }
            if (canDelete) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete scene",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            TextButton(onClick = onToggleExpanded) {
                Text(if (expanded) "Done" else "Edit")
            }
        }

        if (expanded) {
            overlays.forEach { overlay ->
                SfDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = scene.visibility[overlay.id] != false,
                        onCheckedChange = { onSetVisibility(overlay.id, it) },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = overlay.describe(catalog),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Human name for an overlay in the scene list. */
private fun OverlayItem.describe(catalog: List<GraphicsPack>): String = when (this) {
    is OverlayItem.Pack -> catalog.firstOrNull { it.id == packId }?.name ?: "Graphic"
    is OverlayItem.Text -> text.take(28).ifBlank { "Text" }
    is OverlayItem.Image -> "Image"
    is OverlayItem.Gif -> "GIF"
    is OverlayItem.Video -> "Video"
    is OverlayItem.Browser -> "Web overlay"
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 24) name = it },
                label = { Text("Name") },
                placeholder = { Text("Half time") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                shape = CircleShape,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
