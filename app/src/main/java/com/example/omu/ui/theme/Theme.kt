package com.example.omu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = OmuGreenLight,
    onPrimary = Color(0xFF002116),
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = Color(0xFFD1FAE5),
    secondary = OmuBlueLight,
    onSecondary = Color(0xFF071A33),
    secondaryContainer = Color(0xFF1E3A8A),
    onSecondaryContainer = Color(0xFFDBEAFE),
    tertiary = OmuAmberLight,
    onTertiary = Color(0xFF281700),
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = Color(0xFFFFF7CC),
    background = OmuBackgroundDark,
    onBackground = Color(0xFFE5E7EB),
    surface = OmuSurfaceDark,
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = OmuSurfaceVariantDark,
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = OmuOutlineDark,
    outlineVariant = Color(0xFF243142),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF4C1D1D),
    onErrorContainer = Color(0xFFFEE2E2)
)

private val LightColorScheme = lightColorScheme(
    primary = OmuGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF022C22),
    secondary = OmuBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF172554),
    tertiary = OmuAmber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF451A03),
    background = OmuBackgroundLight,
    onBackground = Color(0xFF111827),
    surface = OmuSurfaceLight,
    onSurface = Color(0xFF111827),
    surfaceVariant = OmuSurfaceVariantLight,
    onSurfaceVariant = Color(0xFF475569),
    outline = OmuOutlineLight,
    outlineVariant = Color(0xFFD8E0EA),
    error = Color(0xFFB91C1C),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

@Composable
fun OmuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
