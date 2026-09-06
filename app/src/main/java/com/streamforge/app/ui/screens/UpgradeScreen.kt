package com.streamforge.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.streamforge.app.billing.Entitlement
import com.streamforge.app.billing.Tier
import com.streamforge.app.ui.components.SfBadge
import com.streamforge.app.ui.components.SfCard
import com.streamforge.app.ui.theme.SfTheme

/**
 * The upgrade screen.
 *
 * There is no in-app purchase here, by design: the app is distributed as a direct APK, not
 * through a store, and payment is taken outside it. So this screen has exactly two jobs —
 * make the plans legible enough that someone decides to buy, and then get them talking to a
 * human as quickly as possible. WhatsApp first, because for this customer base that is the
 * channel with the highest chance of an actual reply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpgradeScreen(
    currentTier: Tier,
    entitlement: Entitlement,
    contact: SupportContact,
    activating: Boolean,
    activationError: String?,
    onBack: () -> Unit,
    onWhatsApp: (Tier) -> Unit,
    onCall: () -> Unit,
    onEmail: (Tier) -> Unit,
    onActivate: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(if (currentTier == Tier.PRO) Tier.STUDIO else Tier.PRO) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upgrade") },
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
            if (currentTier != Tier.FREE) {
                CurrentPlanCard(currentTier, entitlement)
            }

            Text(
                text = "Choose a plan",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
            )

            Tier.purchasable.forEach { tier ->
                PlanCard(
                    tier = tier,
                    selected = tier == selected,
                    isCurrent = tier == currentTier,
                    onSelect = { selected = tier },
                )
            }

            Spacer(Modifier.height(20.dp))
            ContactSection(
                selected = selected,
                contact = contact,
                onWhatsApp = onWhatsApp,
                onCall = onCall,
                onEmail = onEmail,
            )

            Spacer(Modifier.height(24.dp))
            ActivationSection(
                code = code,
                onCodeChange = { code = it.uppercase() },
                activating = activating,
                error = activationError,
                onActivate = { onActivate(code) },
            )

            Spacer(Modifier.height(40.dp))
        }
    }
}

/** Where customers reach you. Anything blank simply isn't offered. */
data class SupportContact(
    val whatsApp: String = "",
    val phone: String = "",
    val email: String = "",
) {
    val hasAny: Boolean get() = whatsApp.isNotBlank() || phone.isNotBlank() || email.isNotBlank()
}

@Composable
private fun CurrentPlanCard(tier: Tier, entitlement: Entitlement) {
    SfCard(Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Your plan",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                SfBadge(tier.displayName.uppercase())
            }
            Spacer(Modifier.height(6.dp))
            val days = entitlement.daysRemaining()
            Text(
                text = when {
                    days == null -> "Active — no expiry"
                    days <= 0L -> "Expired. Contact us to renew."
                    days <= 7L -> "Expires in $days day${if (days == 1L) "" else "s"} — renew soon"
                    else -> "Expires in $days days"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (days != null && days <= 7L) SfTheme.semantic.warning
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entitlement.codeHint.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Licence ••••${entitlement.codeHint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SfTheme.semantic.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun PlanCard(tier: Tier, selected: Boolean, isCurrent: Boolean, onSelect: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant

    SfCard(Modifier.padding(top = 10.dp)) {
        Column(
            Modifier
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = borderColor,
                    shape = MaterialTheme.shapes.medium,
                )
                .padding(18.dp)
                .fillMaxWidth()
                .then(Modifier)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tier.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    SfBadge("CURRENT")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    tier.priceLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                tier.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = SfTheme.semantic.textTertiary,
            )
            Spacer(Modifier.height(14.dp))
            tier.features.forEach { feature ->
                Row(
                    Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        feature,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
            ) {
                Text(if (selected) "Selected" else "Choose ${tier.displayName}")
            }
        }
    }
}

@Composable
private fun ContactSection(
    selected: Tier,
    contact: SupportContact,
    onWhatsApp: (Tier) -> Unit,
    onCall: () -> Unit,
    onEmail: (Tier) -> Unit,
) {
    SfCard {
        Column(Modifier.padding(18.dp)) {
            Text(
                "Get ${selected.displayName}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (contact.hasAny) {
                    "Message us and we'll set you up. You'll get a licence code to enter below — " +
                        "usually within a few hours."
                } else {
                    // Honest failure: a build with no contact details configured cannot sell.
                    "No contact details are configured in this build. Set SUPPORT_WHATSAPP, " +
                        "SUPPORT_PHONE or SUPPORT_EMAIL in local.properties and rebuild."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (contact.hasAny) {
                Spacer(Modifier.height(16.dp))
                if (contact.whatsApp.isNotBlank()) {
                    Button(
                        onClick = { onWhatsApp(selected) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WhatsAppGreen,
                            contentColor = androidx.compose.ui.graphics.Color.White,
                        ),
                    ) {
                        Icon(Icons.Filled.Chat, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Message on WhatsApp", fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (contact.phone.isNotBlank()) {
                        OutlinedButton(
                            onClick = onCall,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Call")
                        }
                    }
                    if (contact.email.isNotBlank()) {
                        OutlinedButton(
                            onClick = { onEmail(selected) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Filled.Mail, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Email")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivationSection(
    code: String,
    onCodeChange: (String) -> Unit,
    activating: Boolean,
    error: String?,
    onActivate: () -> Unit,
) {
    SfCard {
        Column(Modifier.padding(18.dp)) {
            Text(
                "Already have a licence code?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                label = { Text("Licence code") },
                placeholder = { Text("SF-PRO-XXXXXXXX") },
                singleLine = true,
                enabled = !activating,
                isError = error != null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onActivate,
                enabled = code.isNotBlank() && !activating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = CircleShape,
            ) {
                if (activating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Activate", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "A code works on one account and one phone. If you change phones, " +
                    "message us and we'll move it across.",
                style = MaterialTheme.typography.bodySmall,
                color = SfTheme.semantic.textTertiary,
                textAlign = TextAlign.Start,
            )
        }
    }
}

private val WhatsAppGreen = androidx.compose.ui.graphics.Color(0xFF25D366)

