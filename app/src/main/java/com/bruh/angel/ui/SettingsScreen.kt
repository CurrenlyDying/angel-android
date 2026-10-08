package com.bruh.angel.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bruh.angel.R
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.model.Provider
import com.bruh.angel.model.ToolNames
import com.bruh.angel.model.ToolPolicy
import com.bruh.angel.ui.chat.ChatViewModel
import com.bruh.angel.ui.chat.toolIcon
import com.bruh.angel.ui.components.Callout
import com.bruh.angel.ui.components.HealthDot
import com.bruh.angel.ui.components.Hint
import com.bruh.angel.ui.components.InfoRow
import com.bruh.angel.ui.components.Pill
import com.bruh.angel.ui.components.SectionCard
import com.bruh.angel.ui.components.angelTopBarColors
import com.bruh.angel.ui.theme.AngelTheme
import com.bruh.angel.ui.theme.mono

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit, onOpenTerminal: () -> Unit, onOpenModels: () -> Unit, onOpenMcp: () -> Unit, onOpenAppearance: () -> Unit = {}) {
    val mcpServers by vm.mcp.servers.collectAsStateWithLifecycle()
    val localModels by vm.localModels.models.collectAsStateWithLifecycle()
    val profile by vm.settings.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val installing by vm.installing.collectAsStateWithLifecycle()
    val shizuku by vm.bridge.status.collectAsStateWithLifecycle()
    val linux by vm.linuxStatus.collectAsStateWithLifecycle()
    val installedDistros by vm.installedDistros.collectAsStateWithLifecycle()
    val progress by vm.installProgress.collectAsStateWithLifecycle()
    val model by vm.draftModel.collectAsStateWithLifecycle()
    val apiKey by vm.draftKey.collectAsStateWithLifecycle()
    val dirty by vm.draftDirty.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val modelStatus by vm.modelStatus.collectAsStateWithLifecycle()
    val checking by vm.checkingModels.collectAsStateWithLifecycle()

    var showKey by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDownload by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var instructions by rememberSaveable(prefs.instructions) { mutableStateOf(prefs.instructions) }
    val connected = shizuku.startsWith("Connected:")
    val linuxReady = linux.startsWith("Ready")
    val locked = busy || installing
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    val leave = { if (dirty) confirmLeave = true else onBack() }
    BackHandler(onBack = leave)
    SecureWindow()
    LaunchedEffect(connected) { if (connected) vm.refreshLinux() }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = angelTopBarColors(),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = leave) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
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
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionCard(title = "Your workspace", subtitle = "Make Angel feel like yours.", icon = R.drawable.ic_sparkle) {
                    InfoRow(title = "Appearance", supporting = "Colors, light & dark mode, glass surfaces, and reading comfort",
                        trailing = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                        onClick = onOpenAppearance)
                }
                // ---- Model ----
                SectionCard(
                    title = "Model",
                    subtitle = "Chat text and approved tool output are sent to the selected provider.",
                    icon = R.drawable.ic_cloud
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Provider.entries.forEach { provider ->
                            FilterChip(
                                selected = provider == profile.provider,
                                enabled = !locked,
                                onClick = { vm.selectProvider(provider) },
                                label = { Text(provider.label) },
                                leadingIcon = if (provider.isLocal) {
                                    { Icon(painterResource(R.drawable.ic_memory), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                } else null
                            )
                        }
                    }
                    if (profile.provider.isLocal) {
                        val activeLocal = localModels.firstOrNull { it.id == profile.model }
                        InfoRow(
                            title = activeLocal?.name ?: "No on-device model selected",
                            supporting = if (activeLocal != null) "Runs with llama.cpp on this phone. No key, no internet." else "Download a GGUF from Hugging Face or add a file you already have.",
                            leading = { HealthDot(ok = activeLocal?.ready == true) },
                            trailing = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
                            onClick = onOpenModels
                        )
                        Button(onClick = onOpenModels) { Text("Manage local models") }
                    } else {
                        Box {
                            OutlinedTextField(
                                value = model,
                                onValueChange = { vm.draftModel.value = it },
                                label = { Text("Model ID") },
                                singleLine = true,
                                enabled = !locked,
                                textStyle = MaterialTheme.typography.bodyLarge.mono(),
                                trailingIcon = if (models.isNotEmpty()) {
                                    { TextButton(onClick = { modelMenu = true }) { Text("Choose") } }
                                } else null,
                                modifier = Modifier.fillMaxWidth()
                            )
                            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                                models.forEach { id ->
                                    DropdownMenuItem(
                                        text = { Text(id, style = MaterialTheme.typography.bodyMedium.mono()) },
                                        onClick = { vm.draftModel.value = id; modelMenu = false }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { vm.draftKey.value = it },
                            label = { Text("${profile.provider.label} API key") },
                            singleLine = true,
                            enabled = !locked,
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (modelStatus.isNotBlank()) Text(modelStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

                        val problems = profile.copy(model = model, apiKey = apiKey).problems()
                        problems.forEach { Callout(it) }
                        Provider.forModel(model)?.takeIf { it != profile.provider && problems.isNotEmpty() }?.let { suggested ->
                            OutlinedButton(enabled = !locked && apiKey.isNotBlank(), onClick = { vm.moveDraftTo(suggested) }) {
                                Text("Save these as ${suggested.label} instead")
                            }
                        }

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !locked && dirty, onClick = { vm.saveDraft() }) { Text("Save") }
                            OutlinedButton(enabled = !checking && apiKey.isNotBlank(), onClick = vm::fetchModels) {
                                Text(if (checking) "Checking…" else "Test key & list models")
                            }
                            if (dirty) TextButton(enabled = !locked, onClick = vm::resetDrafts) { Text("Discard") }
                        }
                        Hint(
                            when {
                                dirty -> "Unsaved changes. The provider choice is saved immediately; model and key are saved with Save."
                                profile.apiKey.isBlank() -> "No API key saved for ${profile.provider.label}."
                                else -> "Saved. The key is encrypted with the Android Keystore and never backed up."
                            },
                            color = if (dirty) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ---- Phone access ----
                SectionCard(title = "Phone access", subtitle = "How Angel reaches the shell. No root, no bootloader changes.", icon = R.drawable.ic_smartphone) {
                    InfoRow(
                        title = "Shizuku",
                        supporting = shizuku,
                        leading = { HealthDot(ok = connected, pulsing = shizuku.startsWith("Connecting")) },
                        trailing = {
                            if (connected) OutlinedButton(enabled = !locked, onClick = { vm.smokeTest(false); onBack() }) { Text("Test") }
                            else Button(enabled = !locked, onClick = vm.bridge::connect) { Text("Connect") }
                        }
                    )
                    if (!connected) Hint("Start Shizuku with wireless debugging or ADB in the Shizuku app. Angel accepts the ADB shell user (uid 2000) only.")
                }

                // ---- Linux ----
                SectionCard(
                    title = "Linux environment",
                    subtitle = "Choose the distribution Angel and the terminal use. Each one installs separately and keeps its own files.",
                    icon = R.drawable.ic_terminal
                ) {
                    LinuxDistro.entries.forEach { d ->
                        InfoRow(
                            title = d.label,
                            supporting = d.tagline + " " + if (d in installedDistros) "Installed." else "${d.downloadMb} MB download, about ${d.diskMb} MB on disk.",
                            leading = { RadioButton(selected = d == prefs.distro, onClick = null) },
                            trailing = { if (d in installedDistros) Pill("Installed") },
                            onClick = if (locked) null else ({ vm.selectDistro(d) })
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow(
                        title = "${prefs.distro.label} (selected)",
                        supporting = if (connected) linux else "Connect Shizuku to check",
                        leading = { HealthDot(ok = if (connected) linuxReady else null, pulsing = installing) },
                        trailing = {
                            if (linuxReady) OutlinedButton(enabled = !locked && connected, onClick = { vm.smokeTest(true); onBack() }) { Text("Test") }
                            else Button(enabled = !locked && connected, onClick = { confirmDownload = true }) { Text(if (installing) "Installing…" else "Install") }
                        }
                    )
                    if (installing) progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (prefs.distro == LinuxDistro.KALI) Callout(
                        "Kali runs without real root. Raw sockets, packet injection and monitor mode do not work, so scanners fall back to TCP connect mode. " +
                            "Only use security tools on systems you own or are authorized to test. The image needs about 1 GB free while installing and a few minutes to unpack."
                    )
                    Hint(
                        "ARM64 proot in /data/local/tmp/angel-linux, owned by the shell user. Proot simulates root: it does not root Android and is not a sandbox. " +
                            "Phone storage is mounted at /sdcard."
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenTerminal) {
                            Icon(painterResource(R.drawable.ic_terminal), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open terminal")
                        }
                        if (linuxReady) TextButton(enabled = !locked && connected, onClick = { confirmDownload = true }) { Text("Reinstall") }
                        if (prefs.distro in installedDistros) TextButton(enabled = !locked && connected, onClick = { confirmRemove = true }) { Text("Remove") }
                    }
                }

                // ---- MCP ----
                SectionCard(title = "MCP servers", subtitle = "Extra tools for the model: search, files, memory and more.", icon = R.drawable.ic_apps) {
                    val ready = mcpServers.count { it.state == com.bruh.angel.mcp.McpState.READY }
                    InfoRow(
                        title = if (mcpServers.isEmpty()) "Browse and install servers" else "${mcpServers.size} configured",
                        supporting = if (mcpServers.isEmpty()) "Installed into Linux automatically, or connect to a hosted server" else "$ready ready",
                        trailing = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
                        onClick = onOpenMcp
                    )
                }

                // ---- Permissions ----
                SectionCard(
                    title = "Tool permissions",
                    subtitle = "Ask shows each command before it runs and lets you edit it. Allow runs without asking. Deny blocks the tool.",
                    icon = R.drawable.ic_shield
                ) {
                    ToolNames.all.forEach { tool ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(toolIcon(tool)), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text(ToolNames.label(tool), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ToolPolicy.entries.forEachIndexed { index, policy ->
                                SegmentedButton(
                                    selected = prefs.policy(tool) == policy,
                                    onClick = { vm.setPolicy(tool, policy) },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ToolPolicy.entries.size),
                                    label = { Text(policy.label) }
                                )
                            }
                        }
                    }
                    if (prefs.policies.values.any { it == ToolPolicy.ALLOW }) Callout(
                        "Allowed tools run model-requested actions without confirmation, and their output is sent to the provider.",
                        danger = prefs.policy(ToolNames.ANDROID) == ToolPolicy.ALLOW
                    )
                }

                // ---- Instructions ----
                SectionCard(title = "Custom instructions", subtitle = "Appended to the system prompt for every new conversation.", icon = R.drawable.ic_edit) {
                    OutlinedTextField(
                        value = instructions,
                        onValueChange = { if (it.length <= 4000) instructions = it },
                        placeholder = { Text("e.g. Reply in French. Prefer Linux for calculations. My home Wi-Fi is …") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = instructions != prefs.instructions,
                            onClick = { vm.savePrefs(prefs.copy(instructions = instructions.trim())) }
                        ) { Text("Save instructions") }
                        Spacer(Modifier.weight(1f))
                        Text("${instructions.length}/4000", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // ---- Data ----
                SectionCard(title = "Data", subtitle = "Conversations live only on this phone (app-private, excluded from backups).", icon = R.drawable.ic_storage) {
                    OutlinedButton(onClick = { confirmDeleteAll = true }) {
                        Icon(painterResource(R.drawable.ic_delete_sweep), contentDescription = null, modifier = Modifier.size(18.dp), tint = AngelTheme.colors.danger)
                        Spacer(Modifier.width(8.dp))
                        Text("Delete all conversations", color = AngelTheme.colors.danger)
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }

    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Save provider changes?") },
        text = { Text("You changed the model or API key for ${profile.provider.label}.") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; vm.saveDraft(onSaved = onBack) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") }
                TextButton(onClick = { confirmLeave = false; vm.resetDrafts(); onBack() }) { Text("Discard") }
            }
        }
    )
    if (confirmDownload) {
        val d = prefs.distro
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text(if (linuxReady) "Reinstall ${d.label}?" else "Download ${d.label}?") },
            text = {
                Text(
                    "Downloads the pinned ARM64 runtime and ${d.label} root filesystem (about ${d.downloadMb} MB) over HTTPS and verifies their SHA-256 hashes. " +
                        "It needs about ${d.requiredFreeBytes / 1_048_576} MB free while installing. " +
                        (if (linuxReady) "Reinstalling replaces this distribution, including files inside it. " else "") +
                        "Other distributions and Android data are not touched."
                )
            },
            confirmButton = { TextButton(onClick = { confirmDownload = false; vm.installLinux(d) }) { Text("Download and verify") } },
            dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("Cancel") } }
        )
    }
    if (confirmRemove) {
        val d = prefs.distro
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${d.label}?") },
            text = { Text("Deletes ${d.label} and every file inside it (including /root) from this phone. Other distributions and Android data are not touched.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.removeLinux(d) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
    if (confirmDeleteAll) AlertDialog(
        onDismissRequest = { confirmDeleteAll = false },
        title = { Text("Delete all conversations?") },
        text = { Text("This removes every saved chat from this phone. It cannot be undone.") },
        confirmButton = { TextButton(onClick = { confirmDeleteAll = false; vm.deleteAllConversations() }) { Text("Delete all") } },
        dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } }
    )
}

/** Blocks screenshots/screen recording while API keys may be visible. */
@Composable
internal fun SecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
