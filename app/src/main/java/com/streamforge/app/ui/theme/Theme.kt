package com.streamforge.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * StreamForge Material 3 theme.
 *
 * Deliberately NOT dynamic colour: this is a branded broadcast tool, and the action-green
 * primary is part of the product's identity. Wallpaper-derived colours would also collide
 * with the red LIVE semantics, which must never drift.
 */

private val SfShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LightColors = lightColorScheme(
    primary = SfActionGreen,
    onPrimary = SfOnAction,
    primaryContainer = SfActionContainer,
    onPrimaryContainer = SfOnActionContainer,
    secondary = SfBrandSecondary,
    onSecondary = SfOnAction,
    tertiary = SfBrandPrimary,
    onTertiary = SfOnAction,
    background = SfBackground,
    onBackground = SfTextPrimary,
    surface = SfSurface,
    onSurface = SfTextPrimary,
    surfaceVariant = SfSurfaceVariant,
    onSurfaceVariant = SfTextSecondary,
    surfaceContainer = SfSurfaceElevated,
    surfaceContainerHigh = SfSurfaceElevated,
    surfaceContainerLow = SfSurface,
    outline = SfOutline,
    outlineVariant = SfDivider,
    error = SfError,
    onError = SfOnAction,
)

private val DarkColors = darkColorScheme(
    primary = SfDarkActionGreen,
    onPrimary = SfDarkOnAction,
    primaryContainer = SfDarkActionContainer,
    onPrimaryContainer = SfDarkOnActionContainer,
    secondary = SfDarkBrandSecondary,
    onSecondary = SfDarkOnAction,
    tertiary = SfDarkBrandPrimary,
    onTertiary = SfOnAction,
    background = SfDarkBackground,
    onBackground = SfDarkTextPrimary,
    surface = SfDarkSurface,
    onSurface = SfDarkTextPrimary,
    surfaceVariant = SfDarkSurfaceVariant,
    onSurfaceVariant = SfDarkTextSecondary,
    surfaceContainer = SfDarkSurfaceElevated,
    surfaceContainerHigh = SfDarkSurfaceElevated,
    surfaceContainerLow = SfDarkSurface,
    outline = SfDarkOutline,
    outlineVariant = SfDarkDivider,
    error = SfDarkError,
    onError = SfOnAction,
)

/**
 * Semantic colours Material 3's scheme has no slot for. Read them through [SfTheme] rather
 * than referencing the raw tokens, so a screen never has to branch on the theme itself.
 */
data class SfSemanticColors(
    val live: androidx.compose.ui.graphics.Color,
    val success: androidx.compose.ui.graphics.Color,
    val warning: androidx.compose.ui.graphics.Color,
    val scheduled: androidx.compose.ui.graphics.Color,
    val textTertiary: androidx.compose.ui.graphics.Color,
)

private val LightSemantics = SfSemanticColors(
    live = SfLive,
    success = SfSuccess,
    warning = SfWarning,
    scheduled = SfScheduled,
    textTertiary = SfTextTertiary,
)

private val DarkSemantics = SfSemanticColors(
    live = SfDarkLive,
    success = SfDarkSuccess,
    warning = SfDarkWarning,
    scheduled = SfDarkScheduled,
    textTertiary = SfDarkTextTertiary,
)

private val LocalSfSemantics = androidx.compose.runtime.staticCompositionLocalOf { LightSemantics }

object SfTheme {
    val semantic: SfSemanticColors
        @Composable get() = LocalSfSemantics.current
}

@Composable
fun StreamForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val semantics = if (darkTheme) DarkSemantics else LightSemantics

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Only an Activity window has system bars to tint. A ComposeView hosted inside a
            // dialog or a bottom sheet has none, and the unchecked cast this used to need
            // would throw there.
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalSfSemantics provides semantics) {
        MaterialTheme(
            colorScheme = colors,
            typography = SfTypography,
            shapes = SfShapes,
            content = content,
        )
    }
}

/**
 * Theme for the camera / live screen chrome. The UI sits on a camera image in both themes,
 * so it is always the dark scheme — a light control bar over live video is unreadable.
 */
@Composable
fun StreamForgeCameraTheme(content: @Composable () -> Unit) =
    StreamForgeTheme(darkTheme = true, content = content)
