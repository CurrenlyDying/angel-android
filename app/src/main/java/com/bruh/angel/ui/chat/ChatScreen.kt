package com.bruh.angel.ui.chat

import com.bruh.angel.linuxenv.LinuxDistro
import android.content.ClipData
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bruh.angel.R
import com.bruh.angel.model.ChatItem
import com.bruh.angel.ui.components.Callout
import com.bruh.angel.ui.components.AngelSurface
import com.bruh.angel.ui.components.HealthDot
import com.bruh.angel.ui.components.InfoRow
import com.bruh.angel.ui.components.SectionCard
import com.bruh.angel.ui.components.angelTopBarColors
import com.bruh.angel.ui.theme.AngelTheme
import kotlinx.coroutines.launch

/** Readable line length: the chat column never grows wider than this, even unfolded. */
private val ContentMaxWidth = 760.dp

/** Starter tasks shown on an empty chat; tapping one fills the composer for review. */
private data class QuickPrompt(val icon: Int, val title: String, val text: String)

private val QuickPrompts = listOf(
    QuickPrompt(R.drawable.ic_battery, "Check my phone", "Check battery health and temperature"),
    QuickPrompt(R.drawable.ic_storage, "Find some space", "What is using the most storage?"),
    QuickPrompt(R.drawable.ic_visibility, "See what I see", "Read my screen and describe what's on it"),
    QuickPrompt(R.drawable.ic_terminal, "Build something", "Install Python in Linux and print its version")
)

/** What the chat needs to know about the rest of the app to show readiness. */
data class AgentStatus(
    val modelLabel: String,
    val providerReady: Boolean,
    val shizukuConnected: Boolean,
    val linuxReady: Boolean,
    val local: Boolean,
    val distro: LinuxDistro = LinuxDistro.DEFAULT
) {
    val ready get() = providerReady && shizukuConnected
}

/** Stateful entry point: wires the ViewModel to the app shell. */
@Composable
fun ChatRoute(viewModel: ChatViewModel = viewModel()) {
    com.bruh.angel.ui.AngelApp(viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    items: List<ChatItem>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    awaitingApproval: Boolean = false,
    status: AgentStatus = AgentStatus("", providerReady = true, shizukuConnected = true, linuxReady = true, local = false),
    onSettings: () -> Unit = {},
    onStop: () -> Unit = {},
    onKillCommand: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onHistory: () -> Unit = {},
    onTerminal: () -> Unit = {}
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    // Index into items of the tool call open in the detail sheet; items are append-only within a conversation.
    var detailIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val detail = detailIndex?.let { items.getOrNull(it) as? ChatItem.ToolCall }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        // safeDrawing includes the IME, so the composer rides above the keyboard.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = angelTopBarColors(),
                title = {
                    Column(
                        Modifier
                            .clickable(onClick = onSettings)
                            .heightIn(min = 48.dp)
                            .padding(vertical = 4.dp, horizontal = 4.dp)
                    ) {
                        Text("Angel", style = MaterialTheme.typography.titleLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HealthDot(ok = status.ready, size = 7.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (status.ready) status.modelLabel else "Set up your workspace",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onHistory) { Icon(painterResource(R.drawable.ic_history), contentDescription = "Conversations") }
                },
                actions = {
                    IconButton(onClick = { detailIndex = null; onNewChat() }, enabled = !busy && items.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = "New chat")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Workspace options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Terminal") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_terminal), null) },
                                onClick = { menu = false; onTerminal() })
                            DropdownMenuItem(text = { Text("Settings & appearance") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_settings), null) },
                                onClick = { menu = false; onSettings() })
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (items.isEmpty()) {
                    HomeContent(status = status, onPick = { draft = it }, onSettings = onSettings)
                } else {
                    MessageList(
                        items = items,
                        busy = busy,
                        awaitingApproval = awaitingApproval,
                        onOpenTool = { detailIndex = it },
                        onKill = onKillCommand
                    )
                }
            }
            Centered {
                Composer(
                    text = draft,
                    onText = { draft = it },
                    busy = busy,
                    onSend = { onSend(draft); draft = "" },
                    onStop = onStop
                )
            }
        }
    }

    detail?.let { call -> ToolDetailSheet(call = call, onDismiss = { detailIndex = null }) }
}

/** Constrains content to [ContentMaxWidth] and centers it; used for every row and the composer. */
@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(), content = content)
    }
}

// ---- Empty state ------------------------------------------------------------------------------

@Composable
private fun HomeContent(status: AgentStatus, onPick: (String) -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .size(76.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()) {}
                Icon(painterResource(R.drawable.ic_sparkle), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text("A little help.\nA lot of possibility.", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            Text(
                "Understand your phone, automate the everyday, or build something new. You stay in control.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))

            if (!status.ready) SetupCard(status, onSettings)

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickPrompts.chunked(2).forEach { prompts ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        prompts.forEach { prompt ->
                            AngelSurface(
                                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 112.dp),
                                onClick = { onPick(prompt.text) },
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(painterResource(prompt.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Text(prompt.title, style = MaterialTheme.typography.titleSmall)
                                    Text(prompt.text, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(status: AgentStatus, onSettings: () -> Unit) {
    SectionCard(title = "Let's get you ready", subtitle = "Connect a model and enable phone access to get started.", icon = R.drawable.ic_shield) {
        InfoRow(
            title = "Connect Shizuku",
            supporting = if (status.shizukuConnected) "Connected as the ADB shell user" else "Start Shizuku, then grant Angel access",
            leading = { HealthDot(ok = status.shizukuConnected) },
            trailing = { Chevron() },
            onClick = onSettings
        )
        InfoRow(
            title = if (status.local) "Choose an on-device model" else "Add a model and API key",
            supporting = if (status.providerReady) status.modelLabel else "Pick a provider in Settings and paste its key",
            leading = { HealthDot(ok = status.providerReady) },
            trailing = { Chevron() },
            onClick = onSettings
        )
        if (status.shizukuConnected && !status.linuxReady) InfoRow(
            title = "Install Linux (optional)",
            supporting = "${status.distro.label} ARM64, about ${status.distro.downloadMb} MB, verified download. Other distros in Settings",
            leading = { HealthDot(ok = null) },
            trailing = { Chevron() },
            onClick = onSettings
        )
    }
}

@Composable
private fun Chevron() {
    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
}

// ---- Message list -----------------------------------------------------------------------------

/** Rows are messages or runs of consecutive tool calls (rendered as one activity card). */
private sealed interface ChatRow {
    val key: String

    data class Message(val index: Int, val item: ChatItem) : ChatRow {
        override val key get() = "m$index"
    }

    data class Tools(val start: Int, val calls: List<IndexedValue<ChatItem.ToolCall>>) : ChatRow {
        override val key get() = "t$start"
    }
}

private fun buildRows(items: List<ChatItem>): List<ChatRow> {
    val rows = ArrayList<ChatRow>(items.size)
    var pending: MutableList<IndexedValue<ChatItem.ToolCall>>? = null
    items.forEachIndexed { index, item ->
        if (item is ChatItem.ToolCall) {
            val list = pending ?: mutableListOf<IndexedValue<ChatItem.ToolCall>>().also { pending = it }
            list += IndexedValue(index, item)
        } else {
            pending?.let { rows += ChatRow.Tools(it.first().index, it.toList()); pending = null }
            rows += ChatRow.Message(index, item)
        }
    }
    pending?.let { rows += ChatRow.Tools(it.first().index, it.toList()) }
    return rows
}

@Composable
private fun MessageList(
    items: List<ChatItem>,
    busy: Boolean,
    awaitingApproval: Boolean,
    onOpenTool: (Int) -> Unit,
    onKill: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val rows = remember(items) { buildRows(items) }
    val last = items.lastOrNull()
    // While a tool runs, its own row shows progress; otherwise say what the agent is doing.
    val indicator = when {
        !busy -> null
        last is ChatItem.ToolCall && last.running -> null
        awaitingApproval -> "Waiting for your approval"
        last is ChatItem.AgentMsg -> "Working…"
        else -> "Thinking…"
    }
    val total = rows.size + (if (indicator != null) 1 else 0)
    val reduceMotion = AngelTheme.preferences.reduceMotion
    val scope = rememberCoroutineScope()
    var previousTotal by remember { mutableStateOf(0) }
    val showLatest by remember { derivedStateOf { listState.canScrollForward } }

    LaunchedEffect(total) {
        val nearEnd = previousTotal == 0 || (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= previousTotal - 2
        previousTotal = total
        if (total > 0 && nearEnd && !listState.isScrollInProgress) {
            if (reduceMotion) listState.scrollToItem(total - 1) else listState.animateScrollToItem(total - 1)
        }
    }

    Box(modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        rows(rows, onOpenTool, onKill)
        if (indicator != null) {
            item(key = "indicator") { Centered { ThinkingIndicator(indicator) } }
        }
    }
    if (showLatest) FilledTonalButton(
        onClick = { scope.launch { if (total > 0) {
            if (reduceMotion) listState.scrollToItem(total - 1) else listState.animateScrollToItem(total - 1)
        } } }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
    ) {
        Icon(painterResource(R.drawable.ic_arrow_downward), null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Latest")
    }
    }
}

private fun LazyListScope.rows(rows: List<ChatRow>, onOpenTool: (Int) -> Unit, onKill: () -> Unit) {
    items(count = rows.size, key = { rows[it].key }, contentType = { rows[it]::class }) { i ->
        when (val row = rows[i]) {
            is ChatRow.Message -> Centered {
                when (val item = row.item) {
                    is ChatItem.UserMsg -> UserBubble(item.text)
                    is ChatItem.AgentMsg -> AgentMessage(item.text)
                    is ChatItem.ToolCall -> Unit // grouped into ChatRow.Tools
                }
            }
            is ChatRow.Tools -> Centered { ToolActivityCard(calls = row.calls, onOpen = onOpenTool, onKill = onKill) }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    val colors = AngelTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Spacer(Modifier.width(40.dp)) // never span the full width, like any chat app
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.large,
            color = colors.userBubble,
            contentColor = colors.onUserBubble
        ) {
            SelectionContainer {
                Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun AgentMessage(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val isError = text.startsWith("⚠️")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.ic_sparkle), null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Angel", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isError) {
            Callout(text.removePrefix("⚠️").trim(), danger = true)
        } else {
            SelectionContainer { MarkdownText(text, Modifier.padding(horizontal = 2.dp)) }
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Angel", text))) } },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painterResource(R.drawable.ic_copy),
                    contentDescription = "Copy message",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator(label: String) {
    if (AngelTheme.preferences.reduceMotion) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val transition = rememberInfiniteTransition(label = "thinking")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart), label = "phase"
    )
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val active = ((phase.toInt() % 3) == i)
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .size(6.dp)
                    .alpha(if (active) 1f else 0.3f)
            ) { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize()) {} }
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Composer ---------------------------------------------------------------------------------

@Composable
private fun Composer(text: String, onText: (String) -> Unit, busy: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val canSend = !busy && text.isNotBlank()
    AngelSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            TextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (busy) "Angel is working…" else "Message Angel…") },
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            Box(Modifier.padding(end = 8.dp, bottom = 8.dp)) {
                if (busy) {
                    FilledIconButton(
                        onClick = onStop,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) { Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop", modifier = Modifier.size(18.dp)) }
                } else {
                    FilledIconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = "Send", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

// ---- Previews ---------------------------------------------------------------------------------

private val previewItems = listOf(
    ChatItem.UserMsg("What's my battery level?"),
    ChatItem.ToolCall("android_shell: dumpsys battery | grep level", "  level: 87", 0),
    ChatItem.ToolCall("android_shell: cat /data/secret", "Permission denied", 1),
    ChatItem.AgentMsg("Your battery is at **87%**.\n\n| Check | Result |\n|---|---|\n| Level | 87% |\n| Temp | 31.2 °C |"),
    ChatItem.UserMsg("Install python in linux"),
    ChatItem.ToolCall("linux_shell: apk add python3", "fetch https://dl-cdn.alpinelinux.org/...\n(1/10) Installing libbz2", 0, running = true)
)

@Preview(name = "Folded (cover)", showBackground = true, device = "spec:width=524dp,height=1146dp")
@Preview(name = "Unfolded (inner)", showBackground = true, device = "spec:width=1007dp,height=1043dp")
@Composable
private fun ChatScreenPreview() {
    AngelTheme {
        ChatScreen(items = previewItems, onSend = {}, busy = true, status = AgentStatus("DeepSeek · deepseek-flash", true, true, true, false))
    }
}

@Preview(name = "Empty", showBackground = true, device = "spec:width=524dp,height=1146dp")
@Composable
private fun EmptyChatPreview() {
    AngelTheme {
        ChatScreen(items = emptyList(), onSend = {}, status = AgentStatus("OpenAI · gpt-6.1-sol · no API key", false, false, false, false))
    }
}
