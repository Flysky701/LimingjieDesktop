package com.landosol.toolbox.desktop.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopPreferences
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopThemeMode

// Palette source: microsoft/vscode extensions/theme-defaults/themes/{light,dark}_modern.json.
// Neutral surfaces stay neutral, including upstream Material components and their tonal containers.
data class DesktopSemanticColors(val ok: Color, val warn: Color, val error: Color, val info: Color, val link: Color, val rail: Color)
val LocalDesktopColors = staticCompositionLocalOf { DesktopSemanticColors(Color(0xff18743c), Color(0xff805500), Color(0xffb42318), Color(0xff005fb8), Color(0xff005fb8), Color(0xfff8f8f8)) }
object DesktopSpacing { val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 24.dp }
object DesktopCorners { val sm = RoundedCornerShape(8.dp); val md = RoundedCornerShape(10.dp); val lg = RoundedCornerShape(14.dp) }

private val light = lightColorScheme(
    primary=Color(0xff005fb8), onPrimary=Color.White, primaryContainer=Color(0xffe5ebf1), onPrimaryContainer=Color(0xff005fb8), inversePrimary=Color(0xff4daafc),
    secondary=Color(0xff616161), onSecondary=Color.White, secondaryContainer=Color(0xffe8e8e8), onSecondaryContainer=Color(0xff3b3b3b),
    tertiary=Color(0xff005fb8), onTertiary=Color.White, tertiaryContainer=Color(0xffbed6ed), onTertiaryContainer=Color(0xff003b73),
    background=Color(0xfff8f8f8), onBackground=Color(0xff3b3b3b), surface=Color.White, onSurface=Color(0xff3b3b3b),
    surfaceVariant=Color(0xfff2f2f2), onSurfaceVariant=Color(0xff616161), surfaceTint=Color.White,
    inverseSurface=Color(0xff313131), inverseOnSurface=Color.White,
    error=Color(0xffb42318), onError=Color.White, errorContainer=Color(0xffffe9e7), onErrorContainer=Color(0xff8b2018),
    outline=Color(0xff767676), outlineVariant=Color(0xffe5e5e5), scrim=Color.Black,
    surfaceBright=Color.White, surfaceDim=Color(0xffe5e5e5), surfaceContainerLowest=Color.White,
    surfaceContainerLow=Color(0xfff8f8f8), surfaceContainer=Color(0xfff2f2f2), surfaceContainerHigh=Color(0xffebebeb), surfaceContainerHighest=Color(0xffe5e5e5),
)
private val dark = darkColorScheme(
    primary=Color(0xff0078d4), onPrimary=Color.White, primaryContainer=Color(0xff264778), onPrimaryContainer=Color(0xffc8dfff), inversePrimary=Color(0xff005fb8),
    secondary=Color(0xff9d9d9d), onSecondary=Color(0xff181818), secondaryContainer=Color(0xff313131), onSecondaryContainer=Color(0xffcccccc),
    tertiary=Color(0xff4daafc), onTertiary=Color(0xff181818), tertiaryContainer=Color(0xff264778), onTertiaryContainer=Color(0xffc8dfff),
    background=Color(0xff181818), onBackground=Color(0xffcccccc), surface=Color(0xff1f1f1f), onSurface=Color(0xffcccccc),
    surfaceVariant=Color(0xff313131), onSurfaceVariant=Color(0xff9d9d9d), surfaceTint=Color(0xff1f1f1f),
    inverseSurface=Color(0xffe5e5e5), inverseOnSurface=Color(0xff1f1f1f),
    error=Color(0xffff9991), onError=Color(0xff181818), errorContainer=Color(0xff472523), onErrorContainer=Color(0xffffc8c3),
    outline=Color(0xff868686), outlineVariant=Color(0xff2b2b2b), scrim=Color.Black,
    surfaceBright=Color(0xff3c3c3c), surfaceDim=Color(0xff181818), surfaceContainerLowest=Color(0xff141414),
    surfaceContainerLow=Color(0xff181818), surfaceContainer=Color(0xff202020), surfaceContainerHigh=Color(0xff2b2b2b), surfaceContainerHighest=Color(0xff313131),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class, ExperimentalMaterial3Api::class)
@Composable fun DesktopTheme(preferences: DesktopPreferences, content: @Composable () -> Unit) {
    val isDark = when(preferences.theme) { DesktopThemeMode.SYSTEM -> isSystemInDarkTheme(); DesktopThemeMode.DARK -> true; DesktopThemeMode.LIGHT -> false }
    val family = remember { if (java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.contains("Microsoft YaHei UI")) FontFamily("Microsoft YaHei UI") else FontFamily.Default }
    val scale = if (preferences.largeText) 1.12f else 1f
    fun style(size: Float, bold: Boolean=false) = TextStyle(fontFamily=family, fontSize=(size*scale).sp, lineHeight=(size*scale*1.5f).sp, fontWeight=if(bold) FontWeight.SemiBold else FontWeight.Normal)
    val typography = Typography(displayLarge=style(22f,true),displayMedium=style(22f,true),displaySmall=style(22f,true),
        headlineLarge=style(22f,true),headlineMedium=style(22f,true),headlineSmall=style(18f,true),
        titleLarge=style(22f,true),titleMedium=style(16f,true),titleSmall=style(14f,true),
        bodyLarge=style(14f),bodyMedium=style(13f),bodySmall=style(12.5f),labelLarge=style(13f,true),labelMedium=style(12.5f),labelSmall=style(12.5f))
    val semantics = if(isDark) DesktopSemanticColors(Color(0xff73c991),Color(0xffe2c08d),Color(0xffff9991),Color(0xff4daafc),Color(0xff4daafc),Color(0xff181818))
        else DesktopSemanticColors(Color(0xff18743c),Color(0xff805500),Color(0xffb42318),Color(0xff005fb8),Color(0xff005fb8),Color(0xfff8f8f8))
    CompositionLocalProvider(LocalDesktopColors provides semantics, LocalMinimumInteractiveComponentSize provides 0.dp) {
        MaterialTheme(colorScheme=if(isDark) dark else light,typography=typography,shapes=Shapes(DesktopCorners.sm,DesktopCorners.sm,DesktopCorners.md,DesktopCorners.lg,DesktopCorners.lg),content=content)
    }
}
