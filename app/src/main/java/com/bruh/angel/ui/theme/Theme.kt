package com.bruh.angel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val AngelShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Access point for the colors that live outside [MaterialTheme.colorScheme]. */
object AngelTheme {
    val colors: AngelColors
        @Composable @ReadOnlyComposable get() = LocalAngelColors.current
    val preferences: ThemePreferences
        @Composable @ReadOnlyComposable get() = LocalAppearance.current.preferences
    val update: (ThemePreferences) -> Unit
        @Composable @ReadOnlyComposable get() = LocalAppearance.current.update
}

private data class AppearanceState(val preferences: ThemePreferences, val update: (ThemePreferences) -> Unit)
private val LocalAppearance = staticCompositionLocalOf { AppearanceState(ThemePreferences(), {}) }

/**
 * App theme. Follows the system light/dark setting but always uses Angel's own palette
 * (no dynamic color), so the brand and status colors are stable across devices.
 */
@Composable
fun AngelTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context.applicationContext) { ThemeStore(context) }
    DisposableEffect(store) { onDispose { store.close() } }
    val preferences by store.settings.collectAsStateWithLifecycle()
    AngelTheme(preferences = preferences, onPreferencesChange = store::update, darkTheme = darkTheme, content = content)
}

@Composable
fun AngelTheme(
    preferences: ThemePreferences,
    onPreferencesChange: (ThemePreferences) -> Unit = {},
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val preferences = preferences.normalized()
    val dark = when (preferences.mode) {
        ThemeMode.SYSTEM -> darkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = if (dark) AngelDarkScheme else AngelLightScheme
    val rawAccent = Color(android.graphics.Color.parseColor("#${preferences.accentHex}"))
    val accent = readableAccent(rawAccent, base.surfaceContainerHighest, preferences.highContrast)
    val container = lerp(base.background, accent, if (dark) 0.22f else 0.14f)
    val scheme = base.copy(
        primary = accent,
        onPrimary = contrastingInk(accent),
        primaryContainer = container,
        onPrimaryContainer = contrastingInk(container),
        inversePrimary = readableAccent(rawAccent, base.inverseSurface, preferences.highContrast),
        onSurfaceVariant = if (preferences.highContrast) base.onSurface else base.onSurfaceVariant,
        outline = if (preferences.highContrast) base.onSurfaceVariant else base.outline,
        outlineVariant = if (preferences.highContrast) base.onSurfaceVariant else base.outlineVariant
    )
    val colors = (if (dark) AngelDarkColors else AngelLightColors).copy(userBubble = container, onUserBubble = contrastingInk(container))
    val radius = preferences.cornerRadius.dp
    val shapes = AngelShapes.copy(
        small = RoundedCornerShape(radius * 0.45f),
        medium = RoundedCornerShape(radius * 0.7f),
        large = RoundedCornerShape(radius),
        extraLarge = RoundedCornerShape(radius + 8.dp)
    )
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalAngelColors provides colors,
        LocalAppearance provides AppearanceState(preferences, onPreferencesChange),
        LocalDensity provides Density(density.density, density.fontScale * preferences.textScale)
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AngelTypography,
            shapes = shapes
        ) {
            CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
                Box(Modifier.fillMaxSize().background(ambientBrush())) { content() }
            }
        }
    }
}

internal fun contrastRatio(first: Color, second: Color): Float {
    val firstLight = first.luminance()
    val secondLight = second.luminance()
    return (maxOf(firstLight, secondLight) + 0.05f) / (minOf(firstLight, secondLight) + 0.05f)
}

internal fun contrastingInk(background: Color): Color =
    if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) Color.White else Color.Black

internal fun readableAccent(accent: Color, surface: Color, highContrast: Boolean): Color {
    val target = if (highContrast) 7f else 4.5f
    val ink = contrastingInk(surface)
    for (step in 0..100) {
        val candidate = lerp(accent, ink, step / 100f)
        if (contrastRatio(candidate, surface) >= target) return candidate
    }
    return ink
}

@Composable
fun ambientBrush(): Brush {
    val scheme = MaterialTheme.colorScheme
    val ambient = AngelTheme.preferences.ambientBackground && !AngelTheme.preferences.highContrast
    return Brush.linearGradient(listOf(
        if (ambient) lerp(scheme.background, scheme.primary, 0.07f) else scheme.background,
        scheme.background,
        if (ambient) lerp(scheme.background, scheme.primary, 0.035f) else scheme.background
    ))
}
