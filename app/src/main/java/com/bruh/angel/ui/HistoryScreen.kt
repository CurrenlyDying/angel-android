package com.bruh.angel.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bruh.angel.R
import com.bruh.angel.model.ConversationMeta
import com.bruh.angel.ui.chat.ChatViewModel
import com.bruh.angel.ui.components.EmptyState
import com.bruh.angel.ui.components.Pill
import com.bruh.angel.ui.components.AngelSurface
import com.bruh.angel.ui.components.angelTopBarColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: ChatViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val conversations by vm.history.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<ConversationMeta?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(conversations, query) {
        val q = query.trim()
        if (q.isEmpty()) conversations else conversations.filter { it.title.contains(q, ignoreCase = true) || it.model.contains(q, ignoreCase = true) }
    }
    val sections = remember(filtered) { filtered.groupBy { bucket(it.updated) } }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Conversations") },
                colors = angelTopBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
                },
                actions = {
                    IconButton(enabled = !busy, onClick = { vm.newChat(); onBack() }) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = "New chat")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            if (conversations.isEmpty()) {
                EmptyState(
                    icon = R.drawable.ic_chat,
                    title = "No conversations yet",
                    body = "Chats are saved here automatically. They stay on this phone.",
                    action = { Button(onClick = onBack) { Text("Start a chat") } }
                )
            } else {
                LazyColumn(
                    Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)
                ) {
                    item(key = "search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            singleLine = true,
                            placeholder = { Text("Search conversations") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                            trailingIcon = if (query.isNotEmpty()) {
                                { IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search") } }
                            } else null,
                            shape = MaterialTheme.shapes.extraLarge
                        )
                    }
                    if (filtered.isEmpty()) {
                        item(key = "none") {
                            Text(
                                "Nothing matches \"${query.trim()}\".",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Bucket.entries.forEach { bucket ->
                        val list = sections[bucket] ?: return@forEach
                        item(key = "h-${bucket.name}") {
                            Text(
                                bucket.label,
                                modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(list, key = { it.id }) { meta ->
                            ConversationRow(
                                meta = meta,
                                enabled = !busy,
                                onOpen = { onOpen(meta.id) },
                                onShare = { scope.launch { vm.transcript(meta.id)?.let { shareText(context, meta.title, it) } } },
                                onDelete = { toDelete = meta }
                            )
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { meta ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text(meta.title) },
            confirmButton = { TextButton(onClick = { vm.deleteConversation(meta.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ConversationRow(meta: ConversationMeta, enabled: Boolean, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    AngelSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            Modifier
                .clickable(enabled = enabled, onClick = onOpen)
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(meta.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(meta.model.ifBlank { meta.provider }, mono = true)
                    Text(
                        "${meta.messages} messages · ${timeLabel(meta.updated)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Share as text") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = { menu = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}

private enum class Bucket(val label: String) { TODAY("Today"), YESTERDAY("Yesterday"), WEEK("This week"), EARLIER("Earlier") }

private fun bucket(time: Long): Bucket {
    val startOfToday = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val day = 24L * 60 * 60 * 1000
    return when {
        time >= startOfToday -> Bucket.TODAY
        time >= startOfToday - day -> Bucket.YESTERDAY
        time >= startOfToday - 6 * day -> Bucket.WEEK
        else -> Bucket.EARLIER
    }
}

private fun timeLabel(time: Long): String = when (bucket(time)) {
    Bucket.TODAY, Bucket.YESTERDAY -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))
    else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
}

private fun shareText(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, text.take(60_000))
    context.startActivity(Intent.createChooser(send, "Share conversation"))
}
