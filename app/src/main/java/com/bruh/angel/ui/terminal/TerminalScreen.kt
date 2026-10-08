package com.bruh.angel.ui.terminal

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bruh.angel.R
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.ui.components.EmptyState
import com.bruh.angel.ui.components.HealthDot
import com.bruh.angel.ui.components.angelTopBarColors
import com.bruh.angel.ui.theme.AngelTheme
import com.bruh.angel.ui.theme.mono
import kotlinx.coroutines.launch

private val quickCommands = mapOf(
    TerminalViewModel.Mode.ANDROID to listOf(
        "id", "getprop ro.product.model", "dumpsys battery", "pm list packages -3",
        "df -h /data", "ps -A | head -20", "cmd wifi status", "settings list global | head -20"
    ),
    TerminalViewModel.Mode.LINUX to listOf(
        "uname -a", "cat /etc/os-release", "python3 --version", "ls -la /sdcard", "df -h"
    )
)

private fun linuxQuickCommands(distro: LinuxDistro): List<String> {
    val base = quickCommands.getValue(TerminalViewModel.Mode.LINUX)
    if (!distro.aptBased) return base + listOf("apk update", "apk add python3", "apk list --installed | head")
    val apt = base + listOf("apt-get update", "apt-get install -y python3", "dpkg -l | head")
    return if (distro == LinuxDistro.KALI) apt + listOf("apt-get install -y nmap", "nmap -sT -p 22,80,443 scanme.nmap.org") else apt
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit, distro: LinuxDistro = LinuxDistro.DEFAULT, vm: TerminalViewModel = viewModel()) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val state by vm.state(mode).collectAsStateWithLifecycle()
    val shizuku by vm.bridge.status.collectAsStateWithLifecycle()
    val connected = shizuku.startsWith("Connected")
    var input by rememberSaveable { mutableStateOf("") }
    var fontSize by rememberSaveable { mutableIntStateOf(13) }
    var historyBack by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val colors = AngelTheme.colors

    LaunchedEffect(distro) { vm.useDistro(distro) }
    LaunchedEffect(mode, connected, distro) { if (connected) vm.ensureStarted(mode) }

    val submit = {
        if (state.running) {
            vm.send(input)
            input = ""
            historyBack = 0
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = colors.terminalBackground,
        topBar = {
            TopAppBar(
                colors = angelTopBarColors().copy(containerColor = colors.terminalBackground, titleContentColor = colors.terminalForeground,
                    navigationIconContentColor = colors.terminalForeground, actionIconContentColor = colors.terminalForeground),
                title = {
                    SingleChoiceSegmentedButtonRow(Modifier.height(36.dp)) {
                        TerminalViewModel.Mode.entries.forEachIndexed { index, m ->
                            SegmentedButton(
                                selected = m == mode,
                                onClick = { vm.select(m) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = TerminalViewModel.Mode.entries.size),
                                label = { Text(if (m == TerminalViewModel.Mode.LINUX) distro.label else m.title, style = MaterialTheme.typography.labelLarge) },
                                icon = {}
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                        HealthDot(ok = if (!connected) false else if (state.running) true else null, pulsing = state.starting)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                !connected -> "Offline"
                                state.starting -> "Starting"
                                state.running -> "Live"
                                else -> "Stopped"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.terminalDim
                        )
                    }
                    IconButton(onClick = {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Terminal", vm.text()))) }
                    }) { Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy all output") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Restart session") }, enabled = connected, onClick = { menu = false; vm.restart() },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(20.dp)) })
                            DropdownMenuItem(text = { Text("Clear screen") }, onClick = { menu = false; vm.clear() },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_delete_sweep), contentDescription = null, modifier = Modifier.size(20.dp)) })
                            DropdownMenuItem(text = { Text("Larger text") }, enabled = fontSize < 22, onClick = { fontSize++ },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_format_size), contentDescription = null, modifier = Modifier.size(20.dp)) })
                            DropdownMenuItem(text = { Text("Smaller text") }, enabled = fontSize > 9, onClick = { fontSize-- },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_format_size), contentDescription = null, modifier = Modifier.size(16.dp)) })
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            if (!connected) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = R.drawable.ic_terminal,
                        title = "Shizuku is not connected",
                        body = "$shizuku\n\nThe terminal runs as the ADB shell user through Shizuku. Start Shizuku, then connect.",
                        action = { Button(onClick = vm.bridge::connect) { Text("Connect / grant Shizuku") } }
                    )
                }
            } else {
                TerminalOutput(
                    lines = state.lines,
                    fontSize = fontSize,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            // Quick commands
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                items(if (mode == TerminalViewModel.Mode.LINUX) linuxQuickCommands(distro) else quickCommands.getValue(mode)) { command ->
                    Surface(
                        onClick = { input = command },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            command,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium.mono(),
                            color = colors.terminalForeground
                        )
                    }
                }
            }

            // Key strip
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Key("Ctrl-C", enabled = state.running, accent = true, onClick = vm::interrupt)
                Key("Ctrl-D", enabled = state.running, onClick = vm::endOfInput)
                Key("Tab", enabled = state.running, onClick = { input += "\t" })
                Key("↑", enabled = vm.historySize > 0, onClick = {
                    if (historyBack < vm.historySize) historyBack++
                    vm.historyEntry(historyBack)?.let { input = it }
                })
                Key("↓", enabled = historyBack > 0, onClick = {
                    if (historyBack > 0) historyBack--
                    input = if (historyBack == 0) "" else vm.historyEntry(historyBack).orEmpty()
                })
                Key("|", enabled = true, onClick = { input += " | " })
                Key("~", enabled = true, onClick = { input += "~" })
                Key("/", enabled = true, onClick = { input += "/" })
                Key("-", enabled = true, onClick = { input += "-" })
                Key("Clear", enabled = true, onClick = vm::clear)
            }

            // Prompt line
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(
                        if (mode == TerminalViewModel.Mode.LINUX) "#" else "$",
                        style = MaterialTheme.typography.bodyLarge.mono(),
                        color = colors.terminalPrompt
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 10.dp),
                        enabled = state.running,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.mono().copy(color = colors.terminalForeground),
                        cursorBrush = SolidColor(colors.terminalPrompt),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) Text(
                                    if (!state.running) "Session not running" else if (mode == TerminalViewModel.Mode.LINUX) "Linux command" else "Android shell command",
                                    style = MaterialTheme.typography.bodyLarge.mono(),
                                    color = colors.terminalDim
                                )
                                inner()
                            }
                        }
                    )
                    FilledIconButton(onClick = submit, enabled = state.running, modifier = Modifier.size(36.dp)) {
                        Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = "Run", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, onClick: () -> Unit, accent: Boolean = false) {
    val colors = AngelTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = if (accent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (accent) MaterialTheme.colorScheme.onErrorContainer else colors.terminalForeground
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium.mono(),
            color = if (enabled) androidx.compose.ui.graphics.Color.Unspecified else colors.terminalDim
        )
    }
}

@Composable
private fun TerminalOutput(lines: List<String>, fontSize: Int, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val colors = AngelTheme.colors
    // Stop following output when the user scrolls back; resume once they reach the bottom again.
    val stopFollowing = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0) follow = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canScroll -> if (!canScroll) follow = true }
    }
    LaunchedEffect(lines) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Box(
        modifier
            .background(colors.terminalBackground)
            .nestedScroll(stopFollowing)
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                itemsIndexed(lines) { _, line ->
                    val typed = line.startsWith(TerminalViewModel.PROMPT)
                    val system = line.startsWith("[") && line.endsWith("]")
                    Text(
                        text = line.ifEmpty { " " },
                        color = when {
                            typed -> colors.terminalPrompt
                            system -> colors.terminalDim
                            else -> colors.terminalForeground
                        },
                        style = MaterialTheme.typography.bodyMedium.mono().copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.35f).sp)
                    )
                }
            }
        }
        if (!follow) {
            SmallFloatingActionButton(
                onClick = {
                    follow = true
                    scope.launch { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
            ) { Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = "Jump to latest output") }
        }
    }
}
