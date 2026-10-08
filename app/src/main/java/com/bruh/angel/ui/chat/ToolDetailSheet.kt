package com.bruh.angel.ui.chat

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bruh.angel.R
import com.bruh.angel.model.ChatItem
import com.bruh.angel.ui.components.Pill
import com.bruh.angel.ui.theme.AngelTheme
import com.bruh.angel.ui.theme.mono
import kotlinx.coroutines.launch

/**
 * Full command and output of one tool call, in a bottom sheet. Updates live while the call runs.
 * Works for the folded and the unfolded screen alike (capped at a readable width).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailSheet(call: ChatItem.ToolCall, onDismiss: () -> Unit) {
    val view = call.view()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var wrap by rememberSaveable { mutableStateOf(false) }
    val output = when {
        call.running && call.output.isEmpty() -> "Waiting for output…"
        call.output.isEmpty() -> "(no output)"
        else -> call.output
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 760.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(toolIcon(view.tool)), contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(view.toolLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                ToolStatusGlyph(view.status, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(view.statusLabel, style = MaterialTheme.typography.labelLarge, color = view.status.color())
            }

            // Command
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            text = view.command,
                            style = MaterialTheme.typography.bodyMedium.mono(),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Command", view.command))) } },
                        modifier = Modifier.size(32.dp)
                    ) { Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy command", modifier = Modifier.size(16.dp)) }
                }
            }

            // Output toolbar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Output", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!call.running) {
                    Spacer(Modifier.width(8.dp))
                    Pill("${call.output.length} chars", mono = true)
                }
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = wrap,
                    onClick = { wrap = !wrap },
                    label = { Text("Wrap") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_wrap_text), contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Output", call.output))) } }) {
                    Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy output", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, view.command.take(80))
                        .putExtra(Intent.EXTRA_TEXT, "$ ${view.command}\n${call.output.take(60_000)}")
                    context.startActivity(Intent.createChooser(send, "Share output"))
                }) { Icon(painterResource(R.drawable.ic_share), contentDescription = "Share output", modifier = Modifier.size(18.dp)) }
            }

            // Output
            val colors = AngelTheme.colors
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(colors.terminalBackground)
                    .verticalScroll(rememberScrollState())
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                    .padding(12.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = output,
                        style = MaterialTheme.typography.bodySmall.mono().copy(lineHeight = 18.sp),
                        color = if (call.output.isEmpty()) colors.terminalDim else colors.terminalForeground,
                        softWrap = wrap,
                        modifier = if (wrap) Modifier.fillMaxWidth() else Modifier
                    )
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}
