package com.wikifm.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val RadioColorScheme = darkColorScheme(
    primary = RadioAmber,
    onPrimary = Color.Black,
    secondary = RadioGreen,
    onSecondary = Color.Black,
    background = BackgroundDark,
    onBackground = OnSurfaceLight,
    surface = SurfaceDark,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceMuted,
    outline = RadioAmberDim
)

val RadioTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = 8.sp,
        color = RadioAmber
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        color = OnSurfaceLight
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = OnSurfaceLight
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        color = OnSurfaceMuted
    )
)

@Composable
fun WikiFMTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RadioColorScheme,
        typography = RadioTypography,
        content = content
    )
}
