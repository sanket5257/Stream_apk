package com.streamforge.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Compose colour tokens.
 *
 * These mirror `res/values/colors.xml` and `res/values-night/colors.xml` one-for-one, so the
 * Compose screens and the remaining XML surfaces (the camera screen's GL view, the overlay
 * bottom sheet) render the same palette. When a token changes, change it in BOTH places or
 * the two halves of the app drift apart.
 *
 * Naming follows the XML: `Sf<Token>` for light, `SfDark<Token>` for the night override.
 */

// --- Brand: YouTube red -------------------------------------------------------------
val SfBrandPrimary = Color(0xFFFF0000)
val SfBrandSecondary = Color(0xFF065FD4)

val SfDarkBrandPrimary = Color(0xFFFF4D4D)
val SfDarkBrandSecondary = Color(0xFF5EA0FF)

// --- Primary ACTION colour (the "Enter Studio" green) -------------------------------
val SfActionGreen = Color(0xFF16B36A)
val SfOnAction = Color(0xFFFFFFFF)
val SfActionContainer = Color(0xFFD6F5E6)
val SfOnActionContainer = Color(0xFF06371F)

val SfDarkActionGreen = Color(0xFF2ED68C)
val SfDarkOnAction = Color(0xFF06371F)
val SfDarkActionContainer = Color(0xFF0E3D2A)
val SfDarkOnActionContainer = Color(0xFFA7F3CF)

// --- Surfaces -----------------------------------------------------------------------
val SfBackground = Color(0xFFF6F7F9)
val SfSurface = Color(0xFFFFFFFF)
val SfSurfaceElevated = Color(0xFFF0F2F5)
val SfSurfaceVariant = Color(0xFFECEEF1)

val SfDarkBackground = Color(0xFF0F0F0F)
val SfDarkSurface = Color(0xFF1C1C1E)
val SfDarkSurfaceElevated = Color(0xFF2A2A2D)
val SfDarkSurfaceVariant = Color(0xFF2F2F33)

// --- Text ---------------------------------------------------------------------------
val SfTextPrimary = Color(0xFF0F0F0F)
val SfTextSecondary = Color(0xFF5F6368)
val SfTextTertiary = Color(0xFF909499)

val SfDarkTextPrimary = Color(0xFFF2F2F3)
val SfDarkTextSecondary = Color(0xFFAAAEB3)
val SfDarkTextTertiary = Color(0xFF76797E)

// --- Lines --------------------------------------------------------------------------
val SfDivider = Color(0xFFE3E5E8)
val SfOutline = Color(0xFFD0D3D8)

val SfDarkDivider = Color(0xFF2E2E31)
val SfDarkOutline = Color(0xFF3A3A3E)

// --- Status / semantic --------------------------------------------------------------
val SfLive = Color(0xFFFF0000)
val SfSuccess = Color(0xFF16B36A)
val SfWarning = Color(0xFFE8A100)
val SfError = Color(0xFFE84B5A)
val SfScheduled = Color(0xFF065FD4)

val SfDarkLive = Color(0xFFFF4D4D)
val SfDarkSuccess = Color(0xFF2ED68C)
val SfDarkWarning = Color(0xFFFFC03D)
val SfDarkError = Color(0xFFFF6E79)
val SfDarkScheduled = Color(0xFF5EA0FF)

// --- Destination brand colours ------------------------------------------------------
// Used only as small accent dots/icons next to a destination's name, never as large fills.
val SfYouTubeRed = Color(0xFFFF0000)
val SfFacebookBlue = Color(0xFF1877F2)
val SfInstagramPink = Color(0xFFE1306C)
val SfCustomPurple = Color(0xFF7C4DFF)

// --- Camera-screen chrome -----------------------------------------------------------
// The live UI always sits on a camera image, so it stays dark in both themes.
val SfScrim = Color(0xCC000000)
val SfChipOnCamera = Color(0x99000000)
val SfOnCamera = Color(0xFFFFFFFF)
val SfOnCameraMuted = Color(0xB3FFFFFF)
