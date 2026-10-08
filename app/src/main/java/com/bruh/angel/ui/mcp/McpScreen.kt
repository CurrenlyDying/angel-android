package com.bruh.angel.ui.mcp

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.bruh.angel.mcp.McpNet
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bruh.angel.R
import com.bruh.angel.mcp.McpCatalog
import com.bruh.angel.mcp.McpCatalogEntry
import com.bruh.angel.mcp.McpKind
import com.bruh.angel.mcp.McpRegistry
import com.bruh.angel.mcp.McpServerState
import com.bruh.angel.mcp.McpSetup
import com.bruh.angel.mcp.McpState
import com.bruh.angel.model.ToolPolicy
import com.bruh.angel.ui.SecureWindow
import com.bruh.angel.ui.chat.ChatViewModel
import com.bruh.angel.ui.components.Callout
import com.bruh.angel.ui.components.EmptyState
import com.bruh.angel.ui.components.Hint
import com.bruh.angel.ui.components.Pill
import com.bruh.angel.ui.components.StatusDot
import com.bruh.angel.ui.components.angelTopBarColors
import com.bruh.angel.ui.theme.AngelTheme
import com.bruh.angel.ui.theme.mono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LAN_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(vm: ChatViewModel, onBack: () -> Unit) {
    SecureWindow()
    val servers by vm.mcp.servers.collectAsStateWithLifecycle()
    val shizuku by vm.bridge.status.collectAsStateWithLifecycle()
    val installed by vm.installedDistros.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val connected = shizuku.startsWith("Connected")
    var tab by rememberSaveable { mutableStateOf(if (servers.isEmpty()) 1 else 0) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showRemote by rememberSaveable { mutableStateOf(false) }
    var showCustom by rememberSaveable { mutableStateOf(false) }
    var installing by remember { mutableStateOf<McpCatalogEntry?>(null) }

    fun toast(text: String) { vm.notice.value = text }

    val context = LocalContext.current
    var pendingStart by remember { mutableStateOf<String?>(null) }
    val lanPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingStart?.let(vm.mcp::start)
        pendingStart = null
    }
    fun startRemote(id: String, url: String) {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()
        val lan = McpNet.isPrivateHost(host) && host != "localhost" && !host.startsWith("127.") && host != "::1"
        if (lan && Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(LAN_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = id
            lanPermission.launch(LAN_PERMISSION)
        } else vm.mcp.start(id)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("MCP servers") },
                colors = angelTopBarColors(),
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_add), contentDescription = "Add a server") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Connect to hosted server") }, leadingIcon = { Icon(painterResource(R.drawable.ic_cloud), null) },
                            onClick = { menu = false; showRemote = true })
                        DropdownMenuItem(text = { Text("Custom command") }, leadingIcon = { Icon(painterResource(R.drawable.ic_code), null) },
                            onClick = { menu = false; showCustom = true })
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.widthIn(max = 760.dp)) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed" + if (servers.isNotEmpty()) " (${servers.size})" else "") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Browse") })
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                if (tab == 0) {
                    InstalledList(servers, onOpen = { detailId = it }, onToggle = vm.mcp::setEnabled, onBrowse = { tab = 1 }, onCancel = { vm.mcp.stop(it) })
                } else {
                    BrowseList(
                        installedNames = servers.map { it.config.name }.toSet(),
                        onPick = { installing = it },
                        onRemote = { showRemote = true }
                    )
                }
            }
        }
    }

    installing?.let { entry ->
        InstallSheet(
            entry = entry, distro = prefs.distro, distroInstalled = prefs.distro in installed, shizukuConnected = connected,
            onDismiss = { installing = null },
            onConfirm = { choice ->
                installing = null
                runCatching {
                    when (choice) {
                        is InstallChoice.Local -> {
                            val config = vm.mcp.add(McpSetup.fromPackage(entry, choice.pkg, choice.values, vm.mcp.taken()))
                            vm.mcp.install(config.id)
                            detailId = config.id
                        }
                        is InstallChoice.Remote -> {
                            val config = vm.mcp.add(McpSetup.remote(entry.name, choice.url, choice.headers, vm.mcp.taken(), entry.description))
                            startRemote(config.id, choice.url)
                            detailId = config.id
                        }
                    }
                    tab = 0
                }.onFailure { toast(it.message ?: "Could not add server") }
            }
        )
    }

    if (showRemote) RemoteDialog(onDismiss = { showRemote = false }) { name, url, headers ->
        showRemote = false
        runCatching {
            val config = vm.mcp.add(McpSetup.remote(name, url, headers, vm.mcp.taken()))
            startRemote(config.id, url)
            tab = 0
            detailId = config.id
        }.onFailure { toast(it.message ?: "Could not add server") }
    }

    if (showCustom) CustomDialog(prefs.distro, onDismiss = { showCustom = false }) { c ->
        showCustom = false
        runCatching {
            val config = vm.mcp.add(McpSetup.custom(c.name, c.command, c.install, c.runtime, c.env, vm.mcp.taken()))
            if (config.installScript.isNotBlank()) vm.mcp.install(config.id) else vm.mcp.start(config.id)
            tab = 0
            detailId = config.id
        }.onFailure { toast(it.message ?: "Could not add server") }
    }

    detailId?.let { id ->
        val state = servers.firstOrNull { it.config.id == id }
        if (state == null) detailId = null
        else ServerSheet(state, vm, onDismiss = { detailId = null })
    }
}

private fun stateColor(s: McpState, colors: com.bruh.angel.ui.theme.AngelColors, off: Color): Color = when (s) {
    McpState.READY -> colors.success
    McpState.STARTING, McpState.INSTALLING -> colors.warning
    McpState.ERROR -> colors.danger
    McpState.NEEDS_INSTALL -> colors.warning
    McpState.OFF -> off
}

private fun stateLabel(s: McpServerState): String = when (s.state) {
    McpState.READY -> if (s.tools.size == 1) "Ready, 1 tool" else "Ready, ${s.tools.size} tools"
    McpState.STARTING -> "Starting…"
    McpState.INSTALLING -> s.message.ifBlank { "Installing…" }
    McpState.ERROR -> s.message.lineSequence().firstOrNull().orEmpty().ifBlank { "Failed" }
    McpState.NEEDS_INSTALL -> s.message.ifBlank { "Needs installation" }
    McpState.OFF -> if (s.config.enabled) "Stopped" else "Off"
}

@Composable
private fun InstalledList(
    servers: List<McpServerState>, onOpen: (String) -> Unit, onToggle: (String, Boolean) -> Unit, onBrowse: () -> Unit, onCancel: (String) -> Unit
) {
    if (servers.isEmpty()) {
        EmptyState(
            icon = R.drawable.ic_apps, title = "No servers yet",
            body = "MCP servers give the model extra tools: web search, files, memory and more. Pick one from Browse and Angel installs and connects it.",
            action = { Button(onClick = onBrowse) { Text("Browse servers") } }
        )
        return
    }
    val colors = AngelTheme.colors
    val off = MaterialTheme.colorScheme.outline
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(servers, key = { it.config.id }) { s ->
            Surface(onClick = { onOpen(s.config.id) }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(stateColor(s.state, colors, off), pulsing = s.state == McpState.STARTING || s.state == McpState.INSTALLING)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(s.config.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Pill(when (s.config.kind) { McpKind.REMOTE -> "hosted"; McpKind.CUSTOM -> "custom"; McpKind.CATALOG -> "local" })
                        }
                        Text(stateLabel(s), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (s.state == McpState.ERROR) colors.danger else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.state == McpState.INSTALLING) s.log.lastOrNull()?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall.mono(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    if (s.state == McpState.INSTALLING) TextButton(onClick = { onCancel(s.config.id) }) { Text("Cancel") }
                    else Switch(checked = s.config.enabled, onCheckedChange = { onToggle(s.config.id, it) })
                }
            }
        }
    }
}

/** Search state for the Browse tab: curated list immediately, registry results on demand. */
private class BrowseState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<McpCatalogEntry>>(emptyList())
    var cursor by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var searched by mutableStateOf(false)
}

@Composable
private fun BrowseList(installedNames: Set<String>, onPick: (McpCatalogEntry) -> Unit, onRemote: () -> Unit) {
    val state = remember { BrowseState() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    fun load(more: Boolean) {
        job?.cancel()
        val q = state.query.trim()
        val cursor = if (more) state.cursor else null
        job = scope.launch {
            state.loading = true; state.error = null
            try {
                val page = McpRegistry.search(q, cursor)
                state.results = if (more) state.results + page.entries else page.entries
                state.cursor = page.nextCursor
                state.searched = true
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state.error = e.message ?: "Could not reach the registry"
            } finally { state.loading = false }
        }
    }
    LaunchedEffect(Unit) { if (!state.searched) load(false) }
    LaunchedEffect(state.query) {
        if (state.searched) { delay(450); load(false) }
    }

    val q = state.query.trim()
    val featured = remember(q) {
        if (q.isEmpty()) McpCatalog.curated
        else McpCatalog.curated.filter { it.name.contains(q, true) || it.description.contains(q, true) || it.category.contains(q, true) }
    }
    val curatedIds = remember { McpCatalog.curated.map { it.id }.toSet() }
    val registry = state.results.filter { it.id !in curatedIds }

    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "search") {
            OutlinedTextField(
                value = state.query, onValueChange = { state.query = it.take(80) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search the MCP registry") }, shape = MaterialTheme.shapes.extraLarge,
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = if (state.query.isNotEmpty()) ({ IconButton(onClick = { state.query = "" }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear") } }) else null
            )
        }
        item(key = "remote") {
            Surface(onClick = onRemote, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_cloud), null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("Already have a server running?", style = MaterialTheme.typography.titleSmall)
                        Hint("Connect by hostname or IP address.")
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), null)
                }
            }
        }
        if (featured.isNotEmpty()) {
            item(key = "h-featured") { SectionLabel("Featured") }
            items(featured, key = { "c-" + it.id }) { e ->
                EntryCard(e, added = e.name in installedNames, onPick = { onPick(e) })
            }
        }
        item(key = "h-registry") { SectionLabel(if (q.isEmpty()) "From the community registry" else "Registry results") }
        if (state.error != null) item(key = "err") {
            Callout(state.error ?: "", danger = true)
            TextButton(onClick = { load(false) }) { Text("Retry") }
        }
        items(registry, key = { "r-" + it.id }) { e -> EntryCard(e, added = e.name in installedNames, onPick = { onPick(e) }) }
        item(key = "foot") {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    state.cursor != null -> OutlinedButton(onClick = { load(true) }) { Text("Load more") }
                    state.searched && registry.isEmpty() && state.error == null -> Hint("No community servers match.")
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
private fun EntryCard(e: McpCatalogEntry, added: Boolean, onPick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (e.description.isNotBlank()) Text(e.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    e.pkg?.let { Pill(it.type.label) }
                    if (e.remote != null) Pill("hosted")
                    if (e.curated) Pill("featured", color = MaterialTheme.colorScheme.primary)
                    if (e.approxMb > 0 && e.pkg != null) Pill("~${e.approxMb} MB")
                    if (e.category.isNotBlank()) Pill(e.category)
                }
            }
            Spacer(Modifier.size(8.dp))
            if (added) Pill("added") else Button(onClick = onPick) { Text(if (e.pkg != null) "Install" else "Connect") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSheet(s: McpServerState, vm: ChatViewModel, onDismiss: () -> Unit) {
    val c = s.config
    val colors = AngelTheme.colors
    var confirmRemove by remember { mutableStateOf(false) }
    var editSecrets by remember { mutableStateOf(false) }
    val busy = s.state == McpState.INSTALLING || s.state == McpState.STARTING
    val canInstall = c.kind != McpKind.REMOTE && (c.source.isNotBlank() || c.installScript.isNotBlank())

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 640.dp, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(stateColor(s.state, colors, MaterialTheme.colorScheme.outline), pulsing = busy)
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleLarge)
                    Text(stateLabel(s) + if (s.serverInfo.isNotBlank() && s.state == McpState.READY) " · ${s.serverInfo}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = c.enabled, enabled = s.state != McpState.INSTALLING, onCheckedChange = { vm.mcp.setEnabled(c.id, it) })
            }
            if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodyMedium)
            if (c.isRemote) Text(c.url, style = MaterialTheme.typography.bodySmall.mono(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.state == McpState.ERROR && s.message.isNotBlank()) Callout(s.message.take(600), danger = true)
            if (s.state == McpState.NEEDS_INSTALL) Callout(s.message.ifBlank { "This server needs to be installed." })

            Text("When the model wants to use this server's tools", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(ToolPolicy.ASK to "Ask me", ToolPolicy.ALLOW to "Allow", ToolPolicy.DENY to "Block")
                options.forEachIndexed { i, (p, label) ->
                    SegmentedButton(selected = c.policy == p, onClick = { vm.mcp.setPolicy(c.id, p) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size), label = { Text(label) })
                }
            }
            Hint("Tool output is untrusted text from the server. \"Allow\" runs its tools without asking each time.")

            if (s.tools.isNotEmpty()) {
                Text("Tools (${s.tools.size})", style = MaterialTheme.typography.labelLarge)
                s.tools.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title.ifBlank { t.name }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = t.name !in c.disabledTools, onCheckedChange = { vm.mcp.setToolEnabled(c.id, t.name, it) })
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = c.enabled && !busy, onClick = { vm.mcp.restart(c.id) }, modifier = Modifier.weight(1f)) { Text("Restart") }
                if (s.state == McpState.INSTALLING) OutlinedButton(onClick = { vm.mcp.stop(c.id) }, modifier = Modifier.weight(1f)) { Text("Cancel install") }
                else if (canInstall) OutlinedButton(enabled = !busy, onClick = { vm.mcp.install(c.id) }, modifier = Modifier.weight(1f)) {
                    Text(if (s.state == McpState.NEEDS_INSTALL) "Install" else "Reinstall")
                }
                OutlinedButton(onClick = { editSecrets = true }, modifier = Modifier.weight(1f)) { Text("Credentials") }
            }
            if (s.log.isNotEmpty()) {
                Text("Log", style = MaterialTheme.typography.labelLarge)
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Text(s.log.takeLast(60).joinToString("\n"), style = MaterialTheme.typography.labelSmall.mono(),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(10.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { confirmRemove = true }, modifier = Modifier.align(Alignment.End)) {
                Text("Remove server", color = colors.danger)
            }
            Spacer(Modifier.size(8.dp))
        }
    }

    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove ${c.name}?") },
        text = { Text(if (c.kind == McpKind.CATALOG) "The server and its files are deleted from Linux." else "The server is removed from Angel.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; onDismiss(); vm.mcp.remove(c.id) }) { Text("Remove", color = colors.danger) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
    )
    if (editSecrets) SecretsDialog(c.env, c.headers, onDismiss = { editSecrets = false }) { env, headers ->
        editSecrets = false
        vm.mcp.updateSecrets(c.id, env, headers)
    }
}
