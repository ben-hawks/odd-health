package dev.oddhealth.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback scheme (teal seed) for when wallpaper colours are unavailable.
private val Light = lightColorScheme(
    primary = Color(0xFF006A64), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9DF2E9), onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A6360), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E4), onSecondaryContainer = Color(0xFF051F1D),
    tertiary = Color(0xFF46617A), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCE5FF), onTertiaryContainer = Color(0xFF001E31),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4FBF9), onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF4FBF9), onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2), onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEFF5F3),
    surfaceContainer = Color(0xFFE9EFED), surfaceContainerHigh = Color(0xFFE3EAE8),
    surfaceContainerHighest = Color(0xFFDDE4E2),
    inverseSurface = Color(0xFF2B3231), inverseOnSurface = Color(0xFFECF2F0), inversePrimary = Color(0xFF81D5CD),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF81D5CD), onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF00504B), onPrimaryContainer = Color(0xFF9DF2E9),
    secondary = Color(0xFFB0CCC8), onSecondary = Color(0xFF1C3532),
    secondaryContainer = Color(0xFF324B48), onSecondaryContainer = Color(0xFFCCE8E4),
    tertiary = Color(0xFFAECAE6), onTertiary = Color(0xFF163349),
    tertiaryContainer = Color(0xFF2E4961), onTertiaryContainer = Color(0xFFCCE5FF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1514), onBackground = Color(0xFFDDE4E2),
    surface = Color(0xFF0E1514), onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF889391), outlineVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFF090F0F), surfaceContainerLow = Color(0xFF161D1C),
    surfaceContainer = Color(0xFF1A2120), surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303635),
    inverseSurface = Color(0xFFDDE4E2), inverseOnSurface = Color(0xFF2B3231), inversePrimary = Color(0xFF006A64),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OddHealthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> Dark
        else -> Light
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
