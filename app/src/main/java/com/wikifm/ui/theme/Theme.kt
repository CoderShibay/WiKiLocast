package com.wikifm.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Scheme = darkColorScheme(
    primary          = AccentAmber,
    onPrimary        = Color.Black,
    secondary        = AccentGreen,
    onSecondary      = Color.Black,
    background       = DeepNavy,
    onBackground     = TextPrimary,
    surface          = GlassSurface,
    onSurface        = TextPrimary,
    surfaceVariant   = GlassSurfaceHi,
    onSurfaceVariant = TextSecondary,
    outline          = GlassBorder,
    error            = AccentRed,
    onError          = Color.White
)

val WikiFMTypography = Typography(
    displayLarge  = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,   fontSize = 26.sp, letterSpacing = 5.sp),
    titleLarge    = TextStyle(fontFamily = FontFamily.Default,   fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium   = TextStyle(fontFamily = FontFamily.Default,   fontWeight = FontWeight.Medium,   fontSize = 15.sp),
    bodyMedium    = TextStyle(fontFamily = FontFamily.Default,   fontWeight = FontWeight.Normal,   fontSize = 13.sp, lineHeight = 20.sp, color = TextSecondary),
    labelSmall    = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal,   fontSize = 10.sp, letterSpacing = 1.5.sp, color = TextMuted)
)

@Composable
fun WikiFMTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = WikiFMTypography, content = content)
}
