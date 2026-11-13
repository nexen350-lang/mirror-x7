package com.scrcpy.remote.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Grouped surface palette mapped onto Material 3 color slots:
 *
 *   background / surface      page backdrop
 *   surfaceContainerLowest    inset card or grouped row surface
 *   surfaceContainerHigh      nested control fill inside a group
 *
 * Light mode places white cards on a cool grey backdrop; dark mode places elevated charcoal cards
 * on a true-black backdrop.
 */

private val LightColors = lightColorScheme(
    // Chosen to maintain >= 4.5:1 contrast with white button labels while remaining legible as
    // link and selection accent text on white card surfaces.
    primary = Color(0xFF0A62D2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EBFF),
    onPrimaryContainer = Color(0xFF00356E),
    secondary = Color(0xFF5856D6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E2FB),
    onSecondaryContainer = Color(0xFF1B1A52),
    error = Color(0xFFFF3B30),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF6E0A05),

    // The grouped page, and the card that sits on it.
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFF2F2F7),
    onSurface = Color(0xFF000000),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEFEFF4),
    surfaceContainerHighest = Color(0xFFE5E5EA),

    // Keep the secondary label's grey and translucency, with at least 4.5:1 contrast after
    // compositing onto the grouped fills.
    onSurfaceVariant = Color(0xBF3C3C43),
    outline = Color(0xFFC6C6C8),
    outlineVariant = Color(0xFFD1D1D6),
)

private val DarkColors = darkColorScheme(
    // Dark mode pulls the other way: the accent has to stay bright to be legible as text on a
    // near-black surface, which leaves it too light to carry white. So the text on it is dark
    // instead — the arrangement Material already uses for dark schemes.
    primary = Color(0xFF0A84FF),
    onPrimary = Color(0xFF001A33),
    primaryContainer = Color(0xFF00458F),
    onPrimaryContainer = Color(0xFFD9EBFF),
    secondary = Color(0xFF5E5CE6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2B2A6B),
    onSecondaryContainer = Color(0xFFE3E2FB),
    error = Color(0xFFFF453A),
    onError = Color.White,
    errorContainer = Color(0xFF7A0F08),
    onErrorContainer = Color(0xFFFFDAD6),

    // True black behind lighter groups: the inverse of Material's dark elevation.
    background = Color(0xFF000000),
    onBackground = Color.White,
    surface = Color(0xFF000000),
    onSurface = Color.White,
    surfaceContainerLowest = Color(0xFF1C1C1E),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),

    onSurfaceVariant = Color(0x99EBEBF5),
    outline = Color(0xFF38383A),
    outlineVariant = Color(0xFF48484A),
)

/** Rounded corner scale for cards, dialogs, controls, and sheets. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(11.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/**
 * Application typography scale.
 *
 * Uses 17sp `bodyLarge` for primary list and form legibility with tightened tracking on display
 * headings, while retaining default `labelSmall` sizing so compact 72dp session tiles do not clip.
 */
private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 41.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 25.sp, lineHeight = 31.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun ScrcpyRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dynamic wallpaper color is disabled so verified contrast ratios and grouped surface hierarchy
    // stay consistent across Android 8 through Android 16.
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(colorScheme = colorScheme, shapes = AppShapes, typography = AppTypography, content = content)
}
