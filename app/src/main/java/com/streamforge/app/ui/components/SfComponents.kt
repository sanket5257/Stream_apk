package com.streamforge.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamforge.app.ui.theme.SfTheme

/**
 * Shared building blocks for the Compose screens. Keeping them here (rather than inlining
 * a Card + Row in each screen) is what makes the app read as one product: a settings row on
 * Home and one in Profile are literally the same component.
 */

/** A section heading with optional trailing action text. */
@Composable
fun SfSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = SfTheme.semantic.textTertiary,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onActionClick != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onActionClick),
            )
        }
    }
}

/**
 * The workhorse settings/navigation row: leading icon in a tinted square, title, subtitle,
 * optional trailing content, chevron.
 */
@Composable
fun SfRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(iconTint.copy(alpha = 0.12f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        } else if (showChevron && onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = SfTheme.semantic.textTertiary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Groups rows into one bordered card, the way iOS/Material settings lists read. */
@Composable
fun SfCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium,
            ),
            content = content,
        )
    }
}

/** Hairline separator that respects the card's inner padding. */
@Composable
fun SfDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 72.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** Small status pill: "LIVE", "Pro", "Coming soon". */
@Composable
fun SfBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    pulsing: Boolean = false,
) {
    // A LIVE badge that breathes reads as "currently happening" at a glance, which is the
    // one piece of state the user checks most often while streaming.
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "badge-pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "badge-alpha",
        ).value
    } else 1f

    Surface(
        modifier = modifier.alpha(alpha),
        color = color.copy(alpha = 0.14f),
        shape = CircleShape,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

/** Coloured status dot, sized for inline use next to a label. */
@Composable
fun SfStatusDot(color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 8.dp) {
    val animated by animateColorAsState(color, label = "status-dot")
    Box(modifier.size(size).background(animated, CircleShape))
}

/** Centred empty state for lists with nothing in them yet. */
@Composable
fun SfEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    RoundedCornerShape(20.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}
