package com.bruh.angel.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bruh.angel.ui.theme.AngelTheme

/** Top bars sit flush on the background; content scrolls underneath without a tonal jump. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun angelTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background.copy(alpha = if (AngelTheme.preferences.highContrast) 1f else 0.94f),
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
)

/** Small circular indicator. [pulsing] fades in and out for "in progress". */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp, pulsing: Boolean = false, label: String? = null) {
    val alpha = if (pulsing && !AngelTheme.preferences.reduceMotion) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f, targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulseAlpha"
        ).value
    } else 1f
    Box(
        modifier
            .size(size)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
    )
}

/** Tri-state dot for setup/health rows: ok (green), problem (amber), unknown/off (dim). */
@Composable
fun HealthDot(ok: Boolean?, modifier: Modifier = Modifier, size: Dp = 8.dp, pulsing: Boolean = false) {
    val colors = AngelTheme.colors
    StatusDot(
        color = when (ok) { true -> colors.success; false -> colors.warning; null -> MaterialTheme.colorScheme.outline },
        modifier = modifier,
        size = size,
        pulsing = pulsing
    )
}

/** Compact label chip: "exit 1", "android", "Q4_K_M". */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest, mono: Boolean = false) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall.let { if (mono) it.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) else it },
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** Grouped settings/content card with a header. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: Int? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AngelSurface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (trailing != null) trailing()
            }
            content()
        }
    }
}

/** Two-line row with optional leading and trailing slots, used inside [SectionCard]. */
@Composable
fun InfoRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    supportingMono: Boolean = false
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) { leading() }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(
                supporting,
                style = MaterialTheme.typography.bodySmall.let { if (supportingMono) it.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) else it },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Muted explanatory paragraph under a control. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Inline warning/notice strip. */
@Composable
fun Callout(text: String, modifier: Modifier = Modifier, danger: Boolean = false, icon: Int = com.bruh.angel.R.drawable.ic_warning) {
    val colors = AngelTheme.colors
    val fg = if (danger) colors.danger else colors.warning
    val bg = if (danger) colors.dangerContainer else colors.warningContainer
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Centered placeholder for empty lists. */
@Composable
fun EmptyState(icon: Int, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}
