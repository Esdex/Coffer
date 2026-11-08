package me.esdex.coffer.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val LocalAmoledMode = compositionLocalOf { false }
val LocalDarkMode = compositionLocalOf { false }

// Light: saturated colours against white surfaces; dark containers carry white text.
private val LightColors = lightColorScheme(
    primary = CofferColors.Primary,
    onPrimary = Color.White,
    primaryContainer = CofferColors.PrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = CofferColors.Accent,
    onSecondary = Color.White,
    secondaryContainer = CofferColors.AccentLight,
    onSecondaryContainer = Color(0xFF002020),
    tertiary = CofferColors.AccentDark,
    onTertiary = Color.White,
)

// Dark: the lighter variants, for contrast against dark surfaces.
private val DarkColors = darkColorScheme(
    primary = CofferColors.PrimaryLight,
    onPrimary = Color(0xFF003547),
    primaryContainer = CofferColors.PrimaryDark,
    onPrimaryContainer = CofferColors.PrimaryLight,
    secondary = CofferColors.AccentLight,
    onSecondary = Color(0xFF00332F),
    secondaryContainer = CofferColors.Accent,
    onSecondaryContainer = Color(0xFFB2EBEE),
    tertiary = CofferColors.Primary,
    onTertiary = Color(0xFF003547),
)

/** The app theme: system, light or dark, wallpaper colours on Android 12+, and pure black for AMOLED. */
@Composable
fun CofferTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val dark = when (appearance.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val dynamic = appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    var colors = when {
        dynamic -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val amoled = dark && appearance.amoled
    if (amoled) {
        colors = colors.copy(background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF1A1A1A))
    } else if (dynamic) {
        // Wallpaper palettes make surfaceVariant close to the surface; a little darker keeps cards apart.
        val sv = colors.surfaceVariant
        colors = colors.copy(surfaceVariant = Color(sv.red * 0.766f, sv.green * 0.766f, sv.blue * 0.766f))
    }
    CompositionLocalProvider(LocalAmoledMode provides amoled, LocalDarkMode provides dark) {
        MaterialTheme(colorScheme = colors, typography = CofferTypography, content = content)
    }
}
