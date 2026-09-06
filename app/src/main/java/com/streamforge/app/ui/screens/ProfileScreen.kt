package com.streamforge.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.streamforge.app.billing.Entitlement
import com.streamforge.app.billing.Tier
import com.streamforge.app.ui.components.SfBadge
import com.streamforge.app.ui.components.SfCard
import com.streamforge.app.ui.components.SfDivider
import com.streamforge.app.ui.components.SfRow
import com.streamforge.app.ui.components.SfSectionHeader
import com.streamforge.app.ui.theme.SfTheme

/** Theme choice, mirroring AppCompatDelegate's three night modes. */
enum class ThemeChoice(val label: String) {
    SYSTEM("System default"), LIGHT("Light"), DARK("Dark")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    username: String,
    deviceName: String,
    versionName: String,
    tier: Tier,
    entitlement: Entitlement,
    themeChoice: ThemeChoice,
    updateSubtitle: String,
    onBack: () -> Unit,
    onThemeChange: (ThemeChoice) -> Unit,
    onUpgrade: () -> Unit,
    onCheckUpdate: () -> Unit,
    onContactSupport: () -> Unit,
    onDiagnostics: () -> Unit,
    onLogout: () -> Unit,
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account") },
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    username,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SfBadge(
                        tier.displayName.uppercase(),
                        color = if (tier == Tier.FREE) SfTheme.semantic.textTertiary
                        else MaterialTheme.colorScheme.primary,
                    )
                    entitlement.daysRemaining()?.let { days ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (days <= 0) "Expired" else "$days days left",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (days <= 7) SfTheme.semantic.warning
                            else SfTheme.semantic.textTertiary,
                        )
                    }
                }
            }

            SfSectionHeader("Plan")
            SfCard {
                SfRow(
                    icon = Icons.Filled.WorkspacePremium,
                    title = if (tier == Tier.FREE) "Upgrade" else "Manage your plan",
                    subtitle = if (tier == Tier.FREE) "Unlock every graphics pack, 1080p and scenes"
                    else "Renew, change plan, or move to a new phone",
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    onClick = onUpgrade,
                )
                SfDivider()
                SfRow(
                    icon = Icons.Filled.SupportAgent,
                    title = "Contact support",
                    subtitle = "Questions, licence help, or a problem with a stream",
                    onClick = onContactSupport,
                )
            }

            SfSectionHeader("App")
            SfCard {
                SfRow(
                    icon = Icons.Filled.Brightness6,
                    title = "Theme",
                    subtitle = themeChoice.label,
                    onClick = { showThemeDialog = true },
                )
                SfDivider()
                SfRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = "Check for updates",
                    subtitle = updateSubtitle,
                    onClick = onCheckUpdate,
                )
                SfDivider()
                SfRow(
                    icon = Icons.Filled.BugReport,
                    title = "Diagnostics",
                    subtitle = "Errors the app recovered from",
                    onClick = onDiagnostics,
                )
                SfDivider()
                SfRow(
                    icon = Icons.Filled.PhoneAndroid,
                    title = "This device",
                    subtitle = deviceName,
                    showChevron = false,
                    onClick = null,
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { confirmLogout = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(50.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Log out")
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "StreamForge v$versionName",
                style = MaterialTheme.typography.bodySmall,
                color = SfTheme.semantic.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Theme") },
            text = {
                Column {
                    ThemeChoice.entries.forEach { choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = choice == themeChoice,
                                onClick = { onThemeChange(choice); showThemeDialog = false },
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text("Close") } },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out") },
            text = {
                Text(
                    "You'll need to log in again to stream from this device. " +
                        "Your licence stays with your account."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) {
                    Text("Log out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}
