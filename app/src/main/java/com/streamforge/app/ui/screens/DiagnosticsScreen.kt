package com.streamforge.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.streamforge.app.ui.components.SfCard
import com.streamforge.app.ui.components.SfSectionHeader
import com.streamforge.app.ui.theme.SfTheme

/**
 * The failure log, in the user's hands.
 *
 * This app deliberately swallows almost everything it can survive — click guards, the main
 * looper guard, a rasterizer that returns null rather than throwing mid-broadcast. That is the
 * right trade for a live tool, but it has a cost that showed up the first time a feature broke
 * in the field: "graphics render not working", with no crash, no message, and no way to get a
 * stack trace off a phone that isn't plugged into a laptop.
 *
 * So the log that every guard already writes is readable here, and copyable in one tap. It is
 * app-private and never uploaded; sharing it is the user's decision, made by pressing Copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    report: String,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onClear: () -> Unit,
) {
    var cleared by remember { mutableStateOf(false) }
    val body = if (cleared) "" else report

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
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
            Text(
                text = "Errors the app caught and carried on from. Send this to support when " +
                    "something doesn't work but the app didn't close.",
                style = MaterialTheme.typography.bodySmall,
                color = SfTheme.semantic.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { onCopy(body.ifBlank { "No errors recorded." }) },
                    enabled = body.isNotBlank(),
                ) { Text("Copy") }
                OutlinedButton(
                    onClick = { onClear(); cleared = true },
                    enabled = body.isNotBlank(),
                ) { Text("Clear") }
            }

            SfSectionHeader("Log")
            SfCard {
                if (body.isBlank()) {
                    Text(
                        "Nothing recorded — the app hasn't caught any errors.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SfTheme.semantic.textTertiary,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    // Monospace and horizontally scrollable: stack traces are fixed-width text
                    // whose meaning is in the frames, and wrapping them makes them unreadable.
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(12.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp),
                            )
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}
