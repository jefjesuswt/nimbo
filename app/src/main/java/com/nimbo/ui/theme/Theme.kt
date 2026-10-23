package com.nimbo.ui.theme

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

// Seeds de design-system.html Theme.SEEDS. Roles aproximan tono M3
// (light primary T40, container T90; dark primary T80, container T30).
// Sin hardcodear en pantallas: todo sale de MaterialTheme.colorScheme.
enum class Seed(val hex: String) { OCEAN("#4A78C2"), MEADOW("#5A9A5E"), DUSK("#8B62BF") }

private fun seedScheme(seed: Seed, dark: Boolean) = when (seed) {
    Seed.OCEAN -> if (!dark) lightColorScheme(
        primary = Color(0xFF445E91), onPrimary = Color.White,
        primaryContainer = Color(0xFFD8E2FF), onPrimaryContainer = Color(0xFF001A40),
        secondary = Color(0xFF555E71), onSecondary = Color.White,
        secondaryContainer = Color(0xFFD9E2F3), onSecondaryContainer = Color(0xFF111C2B),
        tertiary = Color(0xFF6B5778), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFF0DBFF), onTertiaryContainer = Color(0xFF251431),
        error = Color(0xFFBA1A1A), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1A1C1E),
        surfaceVariant = Color(0xFFE0E3EC), onSurfaceVariant = Color(0xFF43474E),
        outline = Color(0xFF74777F), outlineVariant = Color(0xFFC3C6CF),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F3F8),
        surfaceContainer = Color(0xFFEEEFF4), surfaceContainerHigh = Color(0xFFE8EAF0),
        surfaceContainerHighest = Color(0xFFE2E4EA),
        inverseSurface = Color(0xFF2F3033), inverseOnSurface = Color(0xFFF1F0F4),
        inversePrimary = Color(0xFFADC6FF), scrim = Color.Black,
    ) else darkColorScheme(
        primary = Color(0xFFADC6FF), onPrimary = Color(0xFF102F60),
        primaryContainer = Color(0xFF294786), onPrimaryContainer = Color(0xFFD8E2FF),
        secondary = Color(0xFFBDC7DC), onSecondary = Color(0xFF273141),
        secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFD9E2F3),
        tertiary = Color(0xFFD3BFE6), onTertiary = Color(0xFF3B2948),
        tertiaryContainer = Color(0xFF523F60), onTertiaryContainer = Color(0xFFF0DBFF),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        surface = Color(0xFF111315), onSurface = Color(0xFFE2E2E5),
        surfaceVariant = Color(0xFF43474E), onSurfaceVariant = Color(0xFFC3C6CF),
        outline = Color(0xFF8D9099), outlineVariant = Color(0xFF43474E),
        surfaceContainerLowest = Color(0xFF0C0E10), surfaceContainerLow = Color(0xFF191C1E),
        surfaceContainer = Color(0xFF1D2022), surfaceContainerHigh = Color(0xFF282A2D),
        surfaceContainerHighest = Color(0xFF333538),
        inverseSurface = Color(0xFFE2E2E5), inverseOnSurface = Color(0xFF2F3033),
        inversePrimary = Color(0xFF445E91), scrim = Color.Black,
    )
    Seed.MEADOW -> if (!dark) lightColorScheme(
        primary = Color(0xFF406838), onPrimary = Color.White,
        primaryContainer = Color(0xFFC0EFC0), onPrimaryContainer = Color(0xFF002200),
        secondary = Color(0xFF525F4F), onSecondary = Color.White,
        secondaryContainer = Color(0xFFD4E8D0), onSecondaryContainer = Color(0xFF101F0F),
        tertiary = Color(0xFF38656A), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFBCEBF1), onTertiaryContainer = Color(0xFF002023),
        error = Color(0xFFBA1A1A), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        surface = Color(0xFFF8FBF1), onSurface = Color(0xFF191C18),
        surfaceVariant = Color(0xFFDFE4D7), onSurfaceVariant = Color(0xFF434839),
        outline = Color(0xFF73796C), outlineVariant = Color(0xFFC3C8BB),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F4EA),
        surfaceContainer = Color(0xFFECEFE6), surfaceContainerHigh = Color(0xFFE6E9E0),
        surfaceContainerHighest = Color(0xFFE0E4D8),
        inverseSurface = Color(0xFF2E312C), inverseOnSurface = Color(0xFFF0F2E9),
        inversePrimary = Color(0xFFA5D6A0), scrim = Color.Black,
    ) else darkColorScheme(
        primary = Color(0xFFA5D6A0), onPrimary = Color(0xFF11380E),
        primaryContainer = Color(0xFF285020), onPrimaryContainer = Color(0xFFC0EFC0),
        secondary = Color(0xFFB8CCB5), onSecondary = Color(0xFF253423),
        secondaryContainer = Color(0xFF3B4B39), onSecondaryContainer = Color(0xFFD4E8D0),
        tertiary = Color(0xFFA0CFD5), onTertiary = Color(0xFF00373C),
        tertiaryContainer = Color(0xFF1E4E53), onTertiaryContainer = Color(0xFFBCEBF1),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        surface = Color(0xFF101510), onSurface = Color(0xFFE0E4DA),
        surfaceVariant = Color(0xFF434839), onSurfaceVariant = Color(0xFFC3C8BB),
        outline = Color(0xFF8D9386), outlineVariant = Color(0xFF434839),
        surfaceContainerLowest = Color(0xFF0B0F0B), surfaceContainerLow = Color(0xFF181D18),
        surfaceContainer = Color(0xFF1C211C), surfaceContainerHigh = Color(0xFF272B27),
        surfaceContainerHighest = Color(0xFF323632),
        inverseSurface = Color(0xFFE0E4DA), inverseOnSurface = Color(0xFF2E312C),
        inversePrimary = Color(0xFF406838), scrim = Color.Black,
    )
    Seed.DUSK -> if (!dark) lightColorScheme(
        primary = Color(0xFF6A4EB0), onPrimary = Color.White,
        primaryContainer = Color(0xFFE9DDFF), onPrimaryContainer = Color(0xFF22005D),
        secondary = Color(0xFF5D5B6B), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE2E0F0), onSecondaryContainer = Color(0xFF1A1B26),
        tertiary = Color(0xFF7E525D), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFD9E0), onTertiaryContainer = Color(0xFF32101B),
        error = Color(0xFFBA1A1A), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        surface = Color(0xFFFEF7FF), onSurface = Color(0xFF1D1B1E),
        surfaceVariant = Color(0xFFE4E1EC), onSurfaceVariant = Color(0xFF49454E),
        outline = Color(0xFF7A757F), outlineVariant = Color(0xFFC9C5D0),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F2F9),
        surfaceContainer = Color(0xFFF0EDF5), surfaceContainerHigh = Color(0xFFEAE7F1),
        surfaceContainerHighest = Color(0xFFE4E1EC),
        inverseSurface = Color(0xFF322F33), inverseOnSurface = Color(0xFFF5EFF7),
        inversePrimary = Color(0xFFD2BCFF), scrim = Color.Black,
    ) else darkColorScheme(
        primary = Color(0xFFD2BCFF), onPrimary = Color(0xFF3A1D7E),
        primaryContainer = Color(0xFF513896), onPrimaryContainer = Color(0xFFE9DDFF),
        secondary = Color(0xFFC6C4D6), onSecondary = Color(0xFF2F2F3C),
        secondaryContainer = Color(0xFF464654), onSecondaryContainer = Color(0xFFE2E0F0),
        tertiary = Color(0xFFEFB8C5), onTertiary = Color(0xFF4A2530),
        tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD9E0),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        surface = Color(0xFF141215), onSurface = Color(0xFFE7E0E5),
        surfaceVariant = Color(0xFF49454E), onSurfaceVariant = Color(0xFFC9C5D0),
        outline = Color(0xFF948F99), outlineVariant = Color(0xFF49454E),
        surfaceContainerLowest = Color(0xFF0E0D10), surfaceContainerLow = Color(0xFF1C1B1E),
        surfaceContainer = Color(0xFF201F22), surfaceContainerHigh = Color(0xFF2B292D),
        surfaceContainerHighest = Color(0xFF363338),
        inverseSurface = Color(0xFFE7E0E5), inverseOnSurface = Color(0xFF322F33),
        inversePrimary = Color(0xFF6A4EB0), scrim = Color.Black,
    )
}

@Composable
fun NimboTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    seed: Seed = Seed.OCEAN,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> seedScheme(seed, darkTheme)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = NimboTypography,
        shapes = NimboShapes,
        content = content
    )
}
