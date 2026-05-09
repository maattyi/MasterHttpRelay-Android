package com.velum.vpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── VELUM Palette ─────────────────────────────────────────────
val PitchBlack       = Color(0xFF000000)
val DeepCharcoal     = Color(0xFF0C0C0C)
val MatteGrey        = Color(0xFF1A1A1A)
val MatteGreyHi      = Color(0xFF222222)
val Border           = Color(0xFF2A2A2A)

val NeonGreen        = Color(0xFFB2FF05)   // primary accent
val NeonGreenSoft    = Color(0xFF1F2A05)

val TextPrimary      = Color(0xFFF5F7F0)
val TextOnNeon       = Color(0xFF000000)
val TextMuted        = Color(0x80FFFFFF)
val TextFaint        = Color(0x40FFFFFF)

val DangerRed        = Color(0xFFFF4D5E)
val WarmAmber        = Color(0xFFFFE08A)

val LightBg          = Color(0xFFF9FBF7)
val LightSurface     = Color(0xFFFFFFFF)
val LightBorder      = Color(0xFFE0E5DD)
val LightTextPrimary = Color(0xFF121410)
val LightTextMuted   = Color(0xFF656B60)

private val DarkColors = darkColorScheme(
    primary = NeonGreen,
    onPrimary = TextOnNeon,
    primaryContainer = NeonGreenSoft,
    onPrimaryContainer = NeonGreen,
    background = PitchBlack,
    onBackground = TextPrimary,
    surface = MatteGrey,
    onSurface = TextPrimary,
    surfaceVariant = DeepCharcoal,
    onSurfaceVariant = TextMuted,
    error = DangerRed,
    outline = Border,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5A8E00), 
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F5D0),
    onPrimaryContainer = Color(0xFF1A2600),
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = Color(0xFFF0F2ED),
    onSurfaceVariant = LightTextMuted,
    error = DangerRed,
    outline = LightBorder,
)

private val VelumShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
)

private val VelumTypography = Typography(
    displayLarge   = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
    headlineLarge  = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge     = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge      = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelMedium    = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp),
)

@Composable
fun VelumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = VelumShapes,
        typography = VelumTypography,
        content = content,
    )
}
