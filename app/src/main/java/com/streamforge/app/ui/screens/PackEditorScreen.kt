package com.streamforge.app.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.streamforge.app.overlay.OverlayItem
import com.streamforge.app.packs.GraphicsPack
import com.streamforge.app.packs.PackField
import com.streamforge.app.packs.PackFieldType
import com.streamforge.app.packs.PackRasterizer
import com.streamforge.app.packs.PackTheme
import com.streamforge.app.ui.components.SfCard
import com.streamforge.app.ui.components.SfSectionHeader
import com.streamforge.app.ui.theme.SfTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Edit one graphic: its text, its numbers, its logo, and its colourway.
 *
 * The live preview at the top is rendered by the SAME rasterizer that draws the graphic into
 * the broadcast, so what is shown here is exactly what goes out — no separate preview
 * implementation to drift out of step with the real one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackEditorScreen(
    overlay: OverlayItem.Pack,
    definition: GraphicsPack,
    onBack: () -> Unit,
    onValuesChange: (Map<String, String>) -> Unit,
    onThemeChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var values by remember(overlay.id) { mutableStateOf(definition.defaultValues() + overlay.values) }
    var themeKey by remember(overlay.id) { mutableStateOf(overlay.themeKey) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    // Why the preview is empty, when it is. A blank well tells the user nothing and left the
    // last report ("graphics render not working") with no evidence to act on.
    var previewError by remember { mutableStateOf<String?>(null) }

    // Re-render the preview whenever the content changes. Off the main thread: this is real
    // bitmap work, and doing it in composition would jank every keystroke.
    LaunchedEffect(values, themeKey) {
        // Guarded: an exception inside a LaunchedEffect is not contained by Compose — it
        // propagates out of the composition's coroutine and ends the process. The rasterizer
        // handles its own failures, but the theme lookup and bitmap hand-off sit outside it,
        // and a dead preview is never worth closing the app over.
        try {
            val bitmap = withContext(Dispatchers.Default) {
                PackRasterizer.render(
                    context = context,
                    pack = definition,
                    values = values,
                    theme = PackTheme.byKey(themeKey),
                    targetWidthPx = PREVIEW_WIDTH_PX,
                )?.bitmap
            }
            preview = bitmap
            // The rasterizer reports its own reason; surface it rather than leaving the well
            // empty, since this is the same draw that feeds the broadcast.
            previewError = if (bitmap == null) {
                PackRasterizer.lastFailure ?: "This graphic produced nothing to draw."
            } else null
        } catch (t: Throwable) {
            android.util.Log.e("PackEditor", "Rendering the preview failed", t)
            com.streamforge.app.util.CrashReporter
                .recordNonFatal("PackEditor", "rendering the preview", t)
            preview = null
            previewError = "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun commit(next: Map<String, String>) {
        values = next
        onValuesChange(next)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(definition.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove from show",
                            tint = MaterialTheme.colorScheme.error,
                        )
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
            PreviewPanel(preview, previewError, definition)

            val grouped = definition.fields.groupBy { it.group }
            grouped.forEach { (group, fields) ->
                SfSectionHeader(group.ifBlank { "Content" })
                SfCard {
                    Column(Modifier.padding(16.dp)) {
                        fields.forEachIndexed { index, field ->
                            if (index > 0) Spacer(Modifier.height(14.dp))
                            FieldEditor(
                                field = field,
                                value = values[field.key].orEmpty(),
                                onChange = { commit(values + (field.key to it)) },
                            )
                        }
                    }
                }
            }

            SfSectionHeader("Colour")
            SfCard {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Only a handful of themes, so they all fit — no scroll, no picker dialog.
                    PackTheme.ALL.take(5).forEach { theme ->
                        FilterChip(
                            selected = theme.key == themeKey,
                            onClick = { themeKey = theme.key; onThemeChange(theme.key) },
                            label = { Text(theme.name, maxLines = 1) },
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }

            if (definition.actions.isNotEmpty()) {
                SfSectionHeader("Live controls")
                SfCard {
                    Text(
                        text = "This graphic has ${definition.actions.size} live buttons " +
                            "(${definition.actions.take(4).joinToString { it.label }}…). " +
                            "Open the studio and tap the dashboard icon to use them while streaming.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PreviewPanel(preview: Bitmap?, error: String?, definition: GraphicsPack) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // A 16:9 well, so the graphic is judged at the proportions it'll occupy on the
                // actual frame rather than cropped to its own bounding box.
                .aspectRatio(16f / 9f)
                .background(PreviewBackdrop, RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = preview
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Preview of ${definition.name}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(definition.defaultWidth.coerceIn(0.2f, 1f))
                        .padding(8.dp),
                )
            } else if (error != null) {
                // The same draw feeds the broadcast, so a failure here is a failure on air.
                // Saying so — with the reason — is what turns "it doesn't work" into a report
                // that can be acted on.
                Text(
                    "This graphic couldn't be drawn.\n$error\n\n" +
                        "Profile › Diagnostics has the full log.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Text(
                    "Rendering…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Shown at its real size on a 16:9 frame",
            style = MaterialTheme.typography.bodySmall,
            color = SfTheme.semantic.textTertiary,
        )
    }
}

@Composable
private fun FieldEditor(field: PackField, value: String, onChange: (String) -> Unit) {
    when (field.type) {
        PackFieldType.NUMBER -> NumberField(field, value, onChange)
        PackFieldType.IMAGE -> ImageField(field, value, onChange)
        PackFieldType.LINES -> OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= field.maxLength) onChange(it) },
            label = { Text(field.label) },
            supportingText = { Text("One per line. Use “Next headline” in the studio to move through them.") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        PackFieldType.TOGGLE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.Switch(
                checked = value.equals("true", ignoreCase = true),
                onCheckedChange = { onChange(it.toString()) },
            )
        }
        PackFieldType.TEXT -> OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= field.maxLength) onChange(it) },
            label = { Text(field.label) },
            singleLine = true,
            supportingText = { Text("${value.length}/${field.maxLength}") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Numbers get +/- steppers as well as a text field. Typing a score is fine at setup time;
 * nudging it is what people do, and a stepper is much less error-prone than editing digits.
 */
@Composable
private fun NumberField(field: PackField, value: String, onChange: (String) -> Unit) {
    val current = value.toIntOrNull() ?: field.default.toIntOrNull() ?: 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(field.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${field.min}–${field.max}",
                style = MaterialTheme.typography.bodySmall,
                color = SfTheme.semantic.textTertiary,
            )
        }
        StepperButton(Icons.Filled.Remove, "Decrease ${field.label}") {
            onChange((current - 1).coerceAtLeast(field.min).toString())
        }
        Text(
            text = current.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        StepperButton(Icons.Filled.Add, "Increase ${field.label}") {
            onChange((current + 1).coerceAtMost(field.max).toString())
        }
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ImageField(field: PackField, value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Persist read access: without this the URI stops working after a reboot, and the
        // logo silently vanishes from the graphic days later.
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (t: Throwable) {
            android.util.Log.w("PackEditor", "Couldn't persist URI permission", t)
        }
        onChange(uri.toString())
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (value.isBlank()) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                coil.compose.AsyncImage(
                    model = value,
                    contentDescription = field.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(44.dp)
                        .padding(2.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(field.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (value.isBlank()) "Not set" else "Selected",
                style = MaterialTheme.typography.bodySmall,
                color = SfTheme.semantic.textTertiary,
            )
        }
        OutlinedButton(
            onClick = { picker.launch(arrayOf("image/*")) },
            shape = CircleShape,
        ) { Text(if (value.isBlank()) "Choose" else "Change") }
    }
}

private val PreviewBackdrop = Color(0xFF1A1D24)

/** Preview render width. Enough to look sharp on a phone without being wasteful. */
private const val PREVIEW_WIDTH_PX = 900
