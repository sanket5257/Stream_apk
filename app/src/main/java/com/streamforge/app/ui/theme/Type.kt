package com.streamforge.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale. Deliberately tighter and heavier at the top end than the Material default —
 * this is a control surface read at arm's length, often outdoors at a match or an event,
 * so headings and numbers need weight.
 */
private val Sans = FontFamily.SansSerif

val SfTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.4.sp,
    ),
)
