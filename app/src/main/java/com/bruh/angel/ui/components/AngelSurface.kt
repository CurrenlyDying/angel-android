package com.bruh.angel.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.bruh.angel.ui.theme.AngelTheme
import com.bruh.angel.ui.theme.SurfaceStyle

@Composable
fun AngelSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val preferences = AngelTheme.preferences
    val scheme = MaterialTheme.colorScheme
    val glass = preferences.surfaceStyle == SurfaceStyle.GLASS && !preferences.highContrast
    val fill = when {
        glass -> scheme.surfaceContainerLow.copy(alpha = preferences.glassOpacity)
        preferences.surfaceStyle == SurfaceStyle.SOLID -> scheme.surfaceContainerHighest
        else -> scheme.surfaceContainerLow
    }
    val border = BorderStroke(1.dp, if (glass) scheme.onSurface.copy(alpha = 0.16f) else scheme.outlineVariant)
    val inner: @Composable () -> Unit = {
        Box(if (glass) Modifier.background(Brush.linearGradient(listOf(
            Color.White.copy(alpha = 0.09f), Color.Transparent, scheme.primary.copy(alpha = 0.025f)
        ))) else Modifier) { content() }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = fill, border = border,
            contentColor = scheme.onSurface,
            shadowElevation = if (glass) 4.dp else 0.dp, content = inner)
    } else {
        Surface(modifier = modifier, shape = shape, color = fill, border = border,
            contentColor = scheme.onSurface,
            shadowElevation = if (glass) 4.dp else 0.dp, content = inner)
    }
}
