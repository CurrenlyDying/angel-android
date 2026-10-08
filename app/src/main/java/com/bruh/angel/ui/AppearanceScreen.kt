package com.bruh.angel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bruh.angel.R
import com.bruh.angel.ui.components.AngelSurface
import com.bruh.angel.ui.components.SectionCard
import com.bruh.angel.ui.components.angelTopBarColors
import com.bruh.angel.ui.theme.AngelTheme
import com.bruh.angel.ui.theme.SurfaceStyle
import com.bruh.angel.ui.theme.ThemeMode
import com.bruh.angel.ui.theme.ThemePreferences

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val preferences = AngelTheme.preferences
    val update = AngelTheme.update
    var hex by rememberSaveable(preferences.accentHex) { mutableStateOf(preferences.accentHex) }
    var reset by rememberSaveable { mutableStateOf(false) }
    val normalizedHex = ThemePreferences.normalizeAccent(hex)
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Appearance") }, colors = angelTopBarColors(),
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), "Back") } },
                actions = { TextButton(onClick = { reset = true }) { Text("Reset") } }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Make yourself at home.", style = MaterialTheme.typography.headlineMedium)
                    Text("Your colors. Your materials. Your Angel. Changes save automatically.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AngelSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(painterResource(R.drawable.ic_sparkle), null, tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(12.dp).size(24.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Live preview", style = MaterialTheme.typography.titleMedium)
                                Text("${preferences.mode.label} · ${preferences.surfaceStyle.label}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(shape = MaterialTheme.shapes.medium, color = AngelTheme.colors.userBubble) {
                                Text("A little more me.", Modifier.padding(12.dp), color = AngelTheme.colors.onUserBubble)
                            }
                        }
                        Text("A quieter canvas for your next big idea.", style = MaterialTheme.typography.bodyLarge)
                        Text("Text stays readable, even with a custom accent.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SectionCard("Theme", subtitle = "Follow your phone or choose a permanent look.") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(selected = preferences.mode == mode, onClick = { update(preferences.copy(mode = mode)) },
                                label = { Text(mode.label) })
                        }
                    }
                }
                SectionCard("Accent color", subtitle = "Choose a palette or enter any six-digit hex color.") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Mint" to "6EE7D8", "Iris" to "B8A1FF", "Ocean" to "74BFFF", "Ember" to "FFB477", "Rose" to "F5A0BD").forEach { (name, color) ->
                            FilterChip(
                                selected = preferences.accentHex == color,
                                onClick = { update(preferences.copy(accentHex = color)) },
                                leadingIcon = { Box(Modifier.size(16.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor("#$color")))) },
                                label = { Text(name) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = hex, onValueChange = { hex = it.take(7) }, label = { Text("Custom accent") },
                        prefix = { Text("#") }, singleLine = true, isError = normalizedHex == null,
                        supportingText = { Text(if (normalizedHex == null) "Use six hex digits, e.g. B8A1FF." else "Accent tones adapt for contrast in light and dark mode.") },
                        trailingIcon = { TextButton(enabled = normalizedHex != null && normalizedHex != preferences.accentHex,
                            onClick = { normalizedHex?.let { update(preferences.copy(accentHex = it)) } }) { Text("Apply") } },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SectionCard("Surface material", subtitle = "A consistent finish for cards, prompts, and the composer.") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SurfaceStyle.entries.forEach { style ->
                            FilterChip(selected = preferences.surfaceStyle == style,
                                onClick = { update(preferences.copy(surfaceStyle = style)) }, label = { Text(style.label) })
                        }
                    }
                    Text(when (preferences.surfaceStyle) {
                        SurfaceStyle.TONAL -> "Soft, layered surfaces. A calm everyday default."
                        SurfaceStyle.SOLID -> "Opaque surfaces with stronger visual separation."
                        SurfaceStyle.GLASS -> "Translucent, luminous surfaces with fine highlights. Glass-inspired, not Apple's optical blur or refraction."
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (preferences.surfaceStyle == SurfaceStyle.GLASS) {
                        ThemeSlider("Glass opacity", preferences.glassOpacity, 0.6f..0.96f,
                            enabled = !preferences.highContrast) { update(preferences.copy(glassOpacity = it)) }
                        if (preferences.highContrast) Text("High contrast keeps glass surfaces opaque.", style = MaterialTheme.typography.bodySmall)
                    }
                    AppearanceSwitch("Ambient background", "A subtle accent wash behind your content.", preferences.ambientBackground,
                        enabled = !preferences.highContrast) { update(preferences.copy(ambientBackground = it)) }
                }
                SectionCard("Shape & reading", subtitle = "System font-size preferences are still respected.") {
                    ThemeSlider("Corner roundness", preferences.cornerRadius, 8f..32f, unit = "dp") {
                        update(preferences.copy(cornerRadius = it))
                    }
                    ThemeSlider("Text size", preferences.textScale, 0.9f..1.3f) {
                        update(preferences.copy(textScale = it))
                    }
                }
                SectionCard("Accessibility", subtitle = "Style should never come at the expense of clarity.") {
                    AppearanceSwitch("High contrast", "Stronger text and borders; removes transparency and ambient tint.", preferences.highContrast) {
                        update(preferences.copy(highContrast = it))
                    }
                    AppearanceSwitch("Reduce motion", "Disable decorative pulses and screen transitions.", preferences.reduceMotion) {
                        update(preferences.copy(reduceMotion = it))
                    }
                }
                Text("Only appearance changes here. Your models, keys, conversations, and tool permissions stay untouched.",
                    modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
    if (reset) AlertDialog(
        onDismissRequest = { reset = false }, title = { Text("Reset appearance?") },
        text = { Text("Restore Angel's default colors, materials, and reading settings. Everything else stays as it is.") },
        confirmButton = { Button(onClick = { update(ThemePreferences()); hex = ThemePreferences().accentHex; reset = false }) { Text("Reset appearance") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } }
    )
}

@Composable
private fun ThemeSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String = "%",
    enabled: Boolean = true, onCommit: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    val display = if (unit == "dp") "${draft.toInt()} dp" else "${(draft * 100).toInt()}%"
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = draft, onValueChange = { draft = it }, valueRange = range, enabled = enabled,
            onValueChangeFinished = { onCommit(draft) }, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun AppearanceSwitch(title: String, body: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title })
    }
}
