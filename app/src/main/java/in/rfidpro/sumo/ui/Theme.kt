package `in`.rfidpro.sumo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette mirrored from frontend/FRONTEND_PLAN.md §20.
// Sumo on Android leans dark by default (looks better on AMOLED + matches
// the always-on assistant vibe), but we ship both schemes so dynamic-theme
// devices stay happy.

val SumoTeal600 = Color(0xFF0D9488)
val SumoTeal700 = Color(0xFF0F766E)
val SumoCyan500 = Color(0xFF06B6D4)
val SumoCyan600 = Color(0xFF0891B2)
val SumoSlate800 = Color(0xFF1E293B)
val SumoSlate900 = Color(0xFF0F172A)
val SumoZinc100 = Color(0xFFF4F4F5)
val SumoZinc300 = Color(0xFFD4D4D8)
val SumoZinc500 = Color(0xFF71717A)
val SumoRose500 = Color(0xFFF43F5E)

private val SumoDarkColors = darkColorScheme(
    primary = SumoTeal600,
    onPrimary = Color.White,
    secondary = SumoCyan500,
    onSecondary = Color.White,
    background = SumoSlate900,
    onBackground = SumoZinc100,
    surface = SumoSlate800,
    onSurface = SumoZinc100,
    error = SumoRose500,
)

private val SumoLightColors = lightColorScheme(
    primary = SumoTeal700,
    onPrimary = Color.White,
    secondary = SumoCyan600,
    onSecondary = Color.White,
    background = Color.White,
    onBackground = SumoSlate900,
    surface = SumoZinc100,
    onSurface = SumoSlate900,
    error = SumoRose500,
)

@Composable
fun SumoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) SumoDarkColors else SumoLightColors
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
