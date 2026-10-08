package com.bruh.angel.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bruh.angel.R
import com.bruh.angel.model.ChatItem
import com.bruh.angel.model.ToolNames
import com.bruh.angel.ui.components.Pill
import com.bruh.angel.ui.components.AngelSurface
import com.bruh.angel.ui.theme.AngelTheme
import com.bruh.angel.ui.theme.mono

/**
 * One card for a run of consecutive tool calls (the agent usually fires several per turn).
 * Each row is one call; tapping a row opens the detail sheet. The running call shows a live
 * tail of its output so progress is visible without opening anything.
 */
@Composable
fun ToolActivityCard(
    calls: List<IndexedValue<ChatItem.ToolCall>>,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onKill: (() -> Unit)? = null
) {
    val views = calls.map { it.index to it.value.view() }
    val running = views.count { it.second.status == ToolStatus.RUNNING }
    val failed = views.count { it.second.status == ToolStatus.FAILED }
    val skipped = views.count { it.second.status == ToolStatus.DENIED || it.second.status == ToolStatus.CANCELLED }

    AngelSurface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            if (views.size > 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = buildString {
                            append(if (running > 0) "Running step ${views.indexOfFirst { it.second.status == ToolStatus.RUNNING } + 1} of ${views.size}" else "${views.size} steps")
                            if (failed > 0) append(" · $failed failed")
                            if (skipped > 0) append(" · $skipped not run")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            views.forEachIndexed { i, (index, view) ->
                ToolRow(view = view, onClick = { onOpen(index) }, onKill = onKill)
                if (i < views.lastIndex) HorizontalDivider(Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ToolRow(view: ToolView, onClick: () -> Unit, onKill: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolStatusGlyph(view.status, size = 18.dp)
            Spacer(Modifier.width(12.dp))
            Pill(view.toolTag, color = view.status.color().takeIf { view.status == ToolStatus.RUNNING } ?: MaterialTheme.colorScheme.onSurfaceVariant, mono = true)
            Spacer(Modifier.width(8.dp))
            Text(
                text = view.command.lineSequence().firstOrNull().orEmpty().ifBlank { view.toolLabel },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.mono(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            if (view.status != ToolStatus.OK && view.status != ToolStatus.RUNNING) {
                Text(view.statusLabel, style = MaterialTheme.typography.labelSmall, color = view.status.color())
                Spacer(Modifier.width(4.dp))
            }
            if (view.status == ToolStatus.RUNNING && onKill != null && view.tool != ToolNames.SCREEN) {
                FilledTonalIconButton(
                    onClick = onKill,
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Icon(painterResource(R.drawable.ic_stop), contentDescription = "Kill command", modifier = Modifier.size(14.dp)) }
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = "Open details",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        if (view.status == ToolStatus.RUNNING && view.call.output.isNotBlank()) {
            val tail = view.call.output.trimEnd().lines().takeLast(3).joinToString("\n")
            Text(
                text = tail,
                modifier = Modifier.padding(start = 30.dp, top = 6.dp),
                style = MaterialTheme.typography.bodySmall.mono(),
                color = AngelTheme.colors.terminalDim,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 400)
@Composable
private fun ToolActivityCardPreview() {
    com.bruh.angel.ui.theme.AngelTheme {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolActivityCard(
                calls = listOf(
                    IndexedValue(1, ChatItem.ToolCall("android_shell: dumpsys battery | grep level", "  level: 87", 0)),
                    IndexedValue(2, ChatItem.ToolCall("android_shell: cat /data/secret", "Permission denied", 1)),
                    IndexedValue(3, ChatItem.ToolCall("read_screen", "Screen 2076x2152", 0)),
                    IndexedValue(4, ChatItem.ToolCall("linux_shell: apk add python3", "fetch https://dl-cdn...\n(1/10) Installing libbz2", 0, running = true))
                ),
                onOpen = {}
            )
            ToolActivityCard(calls = listOf(IndexedValue(6, ChatItem.ToolCall("linux_shell: uname -a", "Linux", 0))), onOpen = {})
        }
    }
}
