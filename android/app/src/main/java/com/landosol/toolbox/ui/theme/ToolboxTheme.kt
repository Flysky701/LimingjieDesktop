package com.landosol.toolbox.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class AppThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF24272D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EAEE),
    onPrimaryContainer = Color(0xFF202329),
    secondary = Color(0xFF565D69),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9ECF0),
    onSecondaryContainer = Color(0xFF24272D),
    tertiary = Color(0xFF52675E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE3EDE7),
    onTertiaryContainer = Color(0xFF284337),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF202329),
    surface = Color.White,
    onSurface = Color(0xFF202329),
    surfaceVariant = Color(0xFFF0F2F5),
    onSurfaceVariant = Color(0xFF606773),
    outline = Color(0xFF939AA5),
    outlineVariant = Color(0xFFE1E4E9),
    error = Color(0xFFB33A40),
    onError = Color.White,
    errorContainer = Color(0xFFFBE9E9),
    onErrorContainer = Color(0xFF8D2028),
    inverseSurface = Color(0xFF25282E),
    inverseOnSurface = Color(0xFFF4F5F7),
    inversePrimary = Color(0xFFD8DDE6),
    surfaceTint = Color.Transparent,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE5E8ED),
    onPrimary = Color(0xFF202329),
    primaryContainer = Color(0xFF343A43),
    onPrimaryContainer = Color(0xFFEBEEF3),
    secondary = Color(0xFFBDC5D1),
    onSecondary = Color(0xFF252B34),
    secondaryContainer = Color(0xFF353B45),
    onSecondaryContainer = Color(0xFFE5E8ED),
    tertiary = Color(0xFFBCD6C8),
    onTertiary = Color(0xFF263C30),
    tertiaryContainer = Color(0xFF31483B),
    onTertiaryContainer = Color(0xFFD9EBDF),
    background = Color(0xFF111316),
    onBackground = Color(0xFFE8EBF0),
    surface = Color(0xFF1D2025),
    onSurface = Color(0xFFE8EBF0),
    surfaceVariant = Color(0xFF292E35),
    onSurfaceVariant = Color(0xFFABB3BF),
    outline = Color(0xFF777F8C),
    outlineVariant = Color(0xFF363C46),
    error = Color(0xFFFFB4B7),
    onError = Color(0xFF620E1A),
    errorContainer = Color(0xFF4B252C),
    onErrorContainer = Color(0xFFFFDADB),
    inverseSurface = Color(0xFFE8EBF0),
    inverseOnSurface = Color(0xFF24272D),
    inversePrimary = Color(0xFF454C58),
    surfaceTint = Color.Transparent,
)

private fun type(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
)

private fun typography(compact: Boolean) = Typography(
    displayLarge = type(40, 48, FontWeight.SemiBold),
    displayMedium = type(34, 42, FontWeight.SemiBold),
    displaySmall = type(30, 38, FontWeight.SemiBold),
    headlineLarge = type(if (compact) 26 else 30, if (compact) 34 else 38, FontWeight.SemiBold),
    headlineMedium = type(24, 32, FontWeight.SemiBold),
    headlineSmall = type(21, 29, FontWeight.SemiBold),
    titleLarge = type(if (compact) 20 else 22, 28, FontWeight.SemiBold),
    titleMedium = type(16, 24, FontWeight.SemiBold),
    titleSmall = type(14, 22, FontWeight.Medium),
    bodyLarge = type(15, 24),
    bodyMedium = type(if (compact) 13 else 14, if (compact) 21 else 22),
    bodySmall = type(12, 19),
    labelLarge = type(13, 20, FontWeight.Medium),
    labelMedium = type(12, 18, FontWeight.Medium),
    labelSmall = type(11, 17, FontWeight.Medium),
)

private val RegularTypography = typography(compact = false)
private val LandscapeTypography = typography(compact = true)
private val ToolboxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun ToolboxTheme(dark: Boolean, compact: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = if (compact) LandscapeTypography else RegularTypography,
        shapes = ToolboxShapes,
        content = content,
    )
}
