package com.bruh.angel.ui.project

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bruh.angel.R
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.linuxenv.ProjectWorkspace
import com.bruh.angel.model.Project
import com.bruh.angel.ui.chat.ChatViewModel
import com.bruh.angel.ui.components.Callout
import com.bruh.angel.ui.components.ExternalCodeConsentDialog
import com.bruh.angel.ui.components.Hint
import com.bruh.angel.ui.components.InfoRow
import com.bruh.angel.ui.components.Pill
import com.bruh.angel.ui.components.SectionCard
import com.bruh.angel.ui.components.angelTopBarColors
import com.bruh.angel.ui.theme.mono

private val projectSelectionSaver = listSaver<Set<String>, String>(
    save = { it.toList() }, restore = { it.toSet() }
)

/**
 * Create or edit a project. [existing] null = create. A project bundles a working directory, a
 * Linux environment, per-project instructions, the MCP connectors it turns on, and an optional repo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    vm: ChatViewModel,
    existing: Project?,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenChanges: (String) -> Unit
) {
    val servers by vm.mcp.servers.collectAsStateWithLifecycle()
    val installed by vm.installedDistros.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val allSkills by vm.skills.collectAsStateWithLifecycle()

    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var workdir by rememberSaveable(existing?.id) { mutableStateOf(existing?.workdir ?: "/root") }
    var distroId by rememberSaveable(existing?.id) { mutableStateOf(existing?.distroId ?: prefs.distro.id) }
    var repoUrl by rememberSaveable(existing?.id) { mutableStateOf(existing?.repoUrl ?: "") }
    var instructions by rememberSaveable(existing?.id) { mutableStateOf(existing?.instructions ?: "") }
    var connectors by rememberSaveable(existing?.id, stateSaver = projectSelectionSaver) {
        mutableStateOf(existing?.connectors ?: servers.filter { it.config.enabled }.map { it.config.id }.toSet())
    }
    var attachedSkills by rememberSaveable(existing?.id, stateSaver = projectSelectionSaver) {
        mutableStateOf(existing?.skills ?: emptySet())
    }
    var showBrowser by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClone by rememberSaveable(existing?.id) { mutableStateOf(false) }

    val distro = LinuxDistro.fromId(distroId)
    val repoError = if (repoUrl.isBlank()) null else ProjectWorkspace.validateRepoUrl(repoUrl)
    val canSave = name.isNotBlank() && workdir.startsWith("/") && (existing != null || repoError == null)

    fun save() {
        if (!canSave) return
        if (existing == null) {
            val id = vm.createProject(name, workdir, distroId, instructions, connectors, repoUrl, attachedSkills)
            if (repoUrl.isNotBlank()) vm.cloneRepo(id)
            onOpenChat()
        } else {
            vm.updateProject(existing.copy(name = name.trim(), workdir = workdir.trim(), distroId = distroId,
                instructions = instructions.trim(), connectors = connectors, skills = attachedSkills))
            onBack()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New project" else "Project") },
                colors = angelTopBarColors(),
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") } },
                actions = { TextButton(enabled = canSave, onClick = {
                    if (existing == null && repoUrl.isNotBlank()) confirmClone = true else save()
                }) { Text(if (existing == null) "Create" else "Save") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                    label = { Text("Project name") }, modifier = Modifier.fillMaxWidth()
                )

                SectionCard("Working directory", icon = R.drawable.ic_folder_open,
                    subtitle = "Where linux_shell and the terminal run for this project.") {
                    InfoRow(title = workdir, supportingMono = true, supporting = distro.label,
                        trailing = { OutlinedButton(onClick = { showBrowser = true }) { Text("Browse") } })
                }

                SectionCard("Environment", icon = R.drawable.ic_terminal,
                    subtitle = "The Linux distribution this project uses.") {
                    LinuxDistro.entries.forEach { d ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = d.id == distroId, onClick = { distroId = d.id })
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(d.label, style = MaterialTheme.typography.bodyLarge)
                                    if (d in installed) Pill("installed", color = MaterialTheme.colorScheme.primary)
                                }
                                Text(d.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (distro !in installed) Callout("${distro.label} isn't installed yet. Install it in Settings → Linux, or Angel will prompt when a command needs it.")
                }

                SectionCard("Repository", icon = R.drawable.ic_code,
                    subtitle = "Optionally clone a GitHub repo into the working directory.") {
                    if (existing == null) {
                        OutlinedTextField(
                            value = repoUrl, onValueChange = { repoUrl = it.trim() }, singleLine = true,
                            label = { Text("GitHub URL (optional)") }, placeholder = { Text("https://github.com/owner/repo") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            isError = repoError != null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (repoError != null) Callout(repoError, danger = true)
                        Hint("Cloned with --depth 1 into the working directory when the project is created. Git is installed into the distro if needed.")
                    } else if (repoUrl.isNotBlank()) {
                        Text(repoUrl, style = MaterialTheme.typography.bodySmall.mono(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { onOpenChanges(existing.id) }, modifier = Modifier.fillMaxWidth()) { Text("View changes") }
                    } else {
                        Hint("No repository. Use the terminal to run `git init` or `git clone` in the working directory, then view changes here.")
                        OutlinedButton(onClick = { onOpenChanges(existing.id) }, modifier = Modifier.fillMaxWidth()) { Text("View changes") }
                    }
                }

                SectionCard("Instructions", icon = R.drawable.ic_code,
                    subtitle = "Added to the system prompt for chats in this project.") {
                    OutlinedTextField(
                        value = instructions, onValueChange = { if (it.length <= 4000) instructions = it },
                        label = { Text("Project instructions") }, modifier = Modifier.fillMaxWidth(), minLines = 3
                    )
                }

                SectionCard("Skills", icon = R.drawable.ic_sparkle,
                    subtitle = "Capability packs whose instructions are added to this project's chats.") {
                    val enabledSkills = allSkills.filter { it.enabled }
                    if (enabledSkills.isEmpty()) Hint("No skills enabled. Turn them on in Settings → Skills.")
                    else enabledSkills.forEach { skill ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(skill.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(skill.description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(checked = skill.id in attachedSkills, onCheckedChange = { on ->
                                attachedSkills = if (on) attachedSkills + skill.id else attachedSkills - skill.id
                                connectors = if (on) connectors + skill.connectorIds else connectors - skill.connectorIds
                            })
                        }
                    }
                    if (existing != null && attachedSkills.any { id -> allSkills.firstOrNull { it.id == id }?.let {
                        com.bruh.angel.model.SkillCatalog.needsSetup(it.id, distro) &&
                            !com.bruh.angel.model.SkillCatalog.isSetUp(it, distro, servers.map { s -> s.config })
                    } == true })
                        Hint("Set up an attached skill's tools from Settings → Skills so its connectors work.")
                }

                SectionCard("Connectors", icon = R.drawable.ic_apps,
                    subtitle = "MCP servers this project makes available to the agent.") {
                    if (servers.isEmpty()) Hint("No MCP servers installed. Add some in Settings → MCP servers.")
                    else servers.forEach { s ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.config.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (s.config.description.isNotBlank()) Text(s.config.description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(checked = s.config.id in connectors, onCheckedChange = { on ->
                                connectors = if (on) connectors + s.config.id else connectors - s.config.id
                            })
                        }
                    }
                }

                if (existing != null) {
                    Button(onClick = { vm.openProjectInNewChat(existing); onOpenChat() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open in a new chat")
                    }
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.End)) {
                        Text("Delete project", color = com.bruh.angel.ui.theme.AngelTheme.colors.danger)
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }

    if (confirmClone) ExternalCodeConsentDialog(
        title = "Create project and clone repository?",
        details = "Clones $repoUrl into $workdir in ${distro.label}. If Git is missing, Angel downloads and installs Git and its dependencies from this distribution's package repositories.",
        onDismiss = { confirmClone = false },
        onConfirm = { confirmClone = false; save() },
        confirmLabel = "Create and clone"
    )

    if (showBrowser) DirectoryBrowserSheet(vm, distro, workdir, onDismiss = { showBrowser = false }) { picked ->
        workdir = picked
        showBrowser = false
    }

    if (confirmDelete && existing != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${existing.name}?") },
        text = { Text("The project is removed and its chats return to General. Files in the working directory are not deleted.") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; vm.deleteProject(existing.id); onBack() }) {
                Text("Delete", color = com.bruh.angel.ui.theme.AngelTheme.colors.danger)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}
