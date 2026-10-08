package com.bruh.angel.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Angel palette: graphite surfaces with a mint/teal accent. Status colors are fixed (not derived
 * from the scheme) so "running", "ok" and "failed" read the same in both themes.
 */

// ---- Dark -------------------------------------------------------------------------------------
private val DarkBackground = Color(0xFF0E1013)
private val DarkSurfaceLowest = Color(0xFF090B0E)
private val DarkSurfaceLow = Color(0xFF13161B)
private val DarkSurface = Color(0xFF181C22)
private val DarkSurfaceHigh = Color(0xFF1F242B)
private val DarkSurfaceHighest = Color(0xFF272D36)
private val DarkOnSurface = Color(0xFFE7EAEE)
private val DarkOnSurfaceVariant = Color(0xFF9BA4B0)
private val DarkOutline = Color(0xFF434B56)
private val DarkOutlineVariant = Color(0xFF2B323B)

private val MintPrimary = Color(0xFF6EE7D8)
private val MintOnPrimary = Color(0xFF003731)
private val MintPrimaryContainer = Color(0xFF0F4F47)
private val MintOnPrimaryContainer = Color(0xFFBDF5EC)

// ---- Light ------------------------------------------------------------------------------------
private val LightBackground = Color(0xFFF6F7F9)
private val LightSurfaceLowest = Color(0xFFFFFFFF)
private val LightSurfaceLow = Color(0xFFFFFFFF)
private val LightSurface = Color(0xFFEEF1F4)
private val LightSurfaceHigh = Color(0xFFE6EAEE)
private val LightSurfaceHighest = Color(0xFFDFE4E9)
private val LightOnSurface = Color(0xFF171B20)
private val LightOnSurfaceVariant = Color(0xFF5B6572)
private val LightOutline = Color(0xFF8A94A0)
private val LightOutlineVariant = Color(0xFFD3D9DF)

private val TealPrimary = Color(0xFF0F766E)
private val TealOnPrimary = Color(0xFFFFFFFF)
private val TealPrimaryContainer = Color(0xFFCDF5EE)
private val TealOnPrimaryContainer = Color(0xFF04332E)

internal val AngelDarkScheme = darkColorScheme(
    primary = MintPrimary,
    onPrimary = MintOnPrimary,
    primaryContainer = MintPrimaryContainer,
    onPrimaryContainer = MintOnPrimaryContainer,
    inversePrimary = TealPrimary,
    secondary = Color(0xFFA9B4C2),
    onSecondary = Color(0xFF1B2430),
    secondaryContainer = Color(0xFF2A3340),
    onSecondaryContainer = Color(0xFFE0E6EE),
    tertiary = Color(0xFFF5C26B),
    onTertiary = Color(0xFF3F2A00),
    tertiaryContainer = Color(0xFF5A3F00),
    onTertiaryContainer = Color(0xFFFFE1A6),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0000),
    errorContainer = Color(0xFF5C1A14),
    onErrorContainer = Color(0xFFFFDAD6),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkBackground,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceHighest,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkSurfaceLowest,
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHighest,
    surfaceDim = DarkBackground,
    surfaceBright = DarkSurfaceHighest,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = Color(0xFF1B1F24),
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = Color(0xFF000000)
)

internal val AngelLightScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealPrimaryContainer,
    onPrimaryContainer = TealOnPrimaryContainer,
    inversePrimary = MintPrimary,
    secondary = Color(0xFF525C69),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E6EC),
    onSecondaryContainer = Color(0xFF171B20),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE1A6),
    onTertiaryContainer = Color(0xFF2F1F00),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightBackground,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceHighest,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurfaceLowest,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = LightSurfaceHighest,
    surfaceDim = LightSurfaceHighest,
    surfaceBright = LightSurfaceLowest,
    inverseSurface = Color(0xFF2C3137),
    inverseOnSurface = Color(0xFFF0F2F5),
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = Color(0xFF000000)
)

/** Colors outside the Material scheme: tool status, the terminal surface, chat bubbles. */
@Immutable
data class AngelColors(
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val terminalBackground: Color,
    val terminalForeground: Color,
    val terminalPrompt: Color,
    val terminalDim: Color,
    val userBubble: Color,
    val onUserBubble: Color
)

internal val AngelDarkColors = AngelColors(
    success = Color(0xFF4ADE80),
    successContainer = Color(0xFF123524),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF3D2E05),
    danger = Color(0xFFF87171),
    dangerContainer = Color(0xFF4A1A1A),
    terminalBackground = Color(0xFF090B0E),
    terminalForeground = Color(0xFFD7DEE7),
    terminalPrompt = MintPrimary,
    terminalDim = Color(0xFF6B7684),
    userBubble = Color(0xFF263241),
    onUserBubble = Color(0xFFE7EAEE)
)

internal val AngelLightColors = AngelColors(
    success = Color(0xFF15803D),
    successContainer = Color(0xFFDCFCE7),
    warning = Color(0xFFB45309),
    warningContainer = Color(0xFFFEF3C7),
    danger = Color(0xFFB91C1C),
    dangerContainer = Color(0xFFFEE2E2),
    terminalBackground = Color(0xFF0E1116),
    terminalForeground = Color(0xFFD7DEE7),
    terminalPrompt = MintPrimary,
    terminalDim = Color(0xFF6B7684),
    userBubble = Color(0xFFDCE8E6),
    onUserBubble = Color(0xFF171B20)
)

internal val LocalAngelColors = staticCompositionLocalOf { AngelDarkColors }
