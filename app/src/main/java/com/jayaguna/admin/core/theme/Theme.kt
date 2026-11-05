package com.jayaguna.admin.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand tokens from https://jayagunatehnikperkasa.com/Page/home/home.css.
object JayagunaBrand {
    val Red = Color(0xFFC32639)
    val Navy = Color(0xFF11265D)
    val Blue = Color(0xFF0058C6)
    val Gold = Color(0xFFCC9F13)
    val Background = Color(0xFFF8FAFC)
    val Muted = Color(0xFF64748B)
    val Line = Color(0xFFE4E9F1)
}

private val BrandColors = lightColorScheme(
    primary = JayagunaBrand.Red, onPrimary = Color.White,
    primaryContainer = Color(0xFFFCECEE), onPrimaryContainer = Color(0xFF8E1527),
    secondary = JayagunaBrand.Blue, onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF1FD), onSecondaryContainer = JayagunaBrand.Navy,
    tertiary = Color(0xFF856700), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF8F2DF), onTertiaryContainer = Color(0xFF584300),
    background = JayagunaBrand.Background, onBackground = JayagunaBrand.Navy,
    surface = Color.White, onSurface = JayagunaBrand.Navy,
    surfaceVariant = Color(0xFFEDF1F7), onSurfaceVariant = Color(0xFF53647D),
    surfaceTint = Color.Transparent,
    surfaceBright = Color.White, surfaceDim = Color(0xFFF0F3F8),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF3F6FA), surfaceContainerHigh = Color(0xFFEDF1F7),
    surfaceContainerHighest = Color(0xFFE4E9F1),
    outline = Color(0xFF8190A7), outlineVariant = JayagunaBrand.Line,
    inverseSurface = JayagunaBrand.Navy, inverseOnSurface = Color.White,
    inversePrimary = Color(0xFFFFAFB8),
)

private val BrandTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 35.sp, letterSpacing = (-0.7).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp),
)

@Composable
fun JayagunaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BrandColors,
        typography = BrandTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(24.dp)),
        content = content,
    )
}
