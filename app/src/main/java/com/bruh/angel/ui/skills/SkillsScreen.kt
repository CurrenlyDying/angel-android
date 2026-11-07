package com.bruh.angel.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bruh.angel.R
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.model.Skill
import com.bruh.angel.model.SkillCatalog
import com.bruh.angel.ui.chat.ChatViewModel
import com.bruh.angel.ui.components.Callout
import com.bruh.angel.ui.components.ExternalCodeConsentDialog
import com.bruh.angel.ui.components.Hint
import com.bruh.angel.ui.components.Pill
import com.bruh.angel.ui.components.SectionCard
import com.bruh.angel.ui.components.angelTopBarColors
import com.bruh.angel.ui.theme.mono

/**
 * The skills library: reusable capability packs (SKILL.md). Each skill's instructions are injected
 * into the system prompt while it is attached to a project; built-ins also install CLI tools and MCP
 * connectors on demand. Built-ins ship with the app; others are imported from a pasted SKILL.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val skills by vm.skills.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val activeDistro by vm.activeDistro.collectAsStateWithLifecycle()
    val servers by vm.mcp.servers.collectAsStateWithLifecycle()
    val status by vm.skillStatus.collectAsStateWithLifecycle()

    var viewing by remember { mutableStateOf<Skill?>(null) }
    var importing by remember { mutableStateOf(false) }
    var confirmSetup by rememberSaveable { mutableStateOf<String?>(null) }
    var setupDistroId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Skills") },
                colors = angelTopBarColors(),
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") } },
                actions = { IconButton(onClick = { importing = true }) { Icon(painterResource(R.drawable.ic_add), contentDescription = "Import skill") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Hint("Skills are reusable capability packs. Attach one to a project (in the project's Skills section) to add its instructions, and set it up to install the tools it needs.")
                if (status != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(status!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                skills.forEach { skill ->
                    SkillCard(
                        skill = skill,
                        attachedCount = projects.count { skill.id in it.skills },
                        needsSetup = SkillCatalog.needsSetup(skill.id, activeDistro),
                        setupReady = SkillCatalog.isSetUp(skill, activeDistro, servers.map { it.config }),
                        distroLabel = activeDistro.label,
                        busy = status != null,
                        onToggle = { vm.setSkillEnabled(skill.id, it) },
                        onView = { viewing = skill },
                        onSetUp = { setupDistroId = activeDistro.id; confirmSetup = skill.id },
                        onDelete = { vm.deleteSkill(skill.id) }
                    )
                }
            }
        }
    }

    skills.firstOrNull { it.id == confirmSetup }?.let { skill ->
        val target = LinuxDistro.fromId(setupDistroId ?: activeDistro.id)
        ExternalCodeConsentDialog(
            title = "Set up ${skill.name}?",
            details = "Downloads analysis tools from ${target.label}'s package repositories and builds the pinned radare2 MCP connector from github.com/radareorg/radare2-mcp. Installs into ${target.label}; the tools can access Linux files and /sdcard. This can take several minutes.",
            onDismiss = { confirmSetup = null },
            onConfirm = { confirmSetup = null; vm.setUpSkill(skill.id, target) }
        )
    }

    viewing?.let { skill ->
        ModalBottomSheet(onDismissRequest = { viewing = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(skill.name, style = MaterialTheme.typography.titleLarge)
                Text(skill.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(skill.instructions, style = MaterialTheme.typography.bodySmall.mono())
            }
        }
    }

    if (importing) ImportSkillDialog(onDismiss = { importing = false }, onImport = { text -> vm.importSkill(text) })
}

@Composable
private fun SkillCard(
    skill: Skill,
    attachedCount: Int,
    needsSetup: Boolean,
    setupReady: Boolean,
    distroLabel: String,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onView: () -> Unit,
    onSetUp: () -> Unit,
    onDelete: () -> Unit
) {
    SectionCard(
        title = skill.name,
        subtitle = skill.description,
        icon = if (skill.id == SkillCatalog.REVERSE_ENGINEERING) R.drawable.ic_shield else R.drawable.ic_sparkle,
        trailing = { Switch(checked = skill.enabled, onCheckedChange = onToggle) }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(if (skill.builtin) "built-in" else "imported")
            if (attachedCount > 0) Pill("attached to $attachedCount", color = MaterialTheme.colorScheme.primary)
            if (needsSetup) Pill(if (setupReady) "tools ready" else "needs setup",
                color = if (setupReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onView) { Text("View") }
            if (needsSetup) OutlinedButton(enabled = !busy, onClick = onSetUp) {
                Text(if (setupReady) "Re-run setup" else "Set up tools")
            }
            if (!skill.builtin) TextButton(onClick = onDelete) {
                Text("Delete", color = com.bruh.angel.ui.theme.AngelTheme.colors.danger)
            }
        }
        if (needsSetup && !setupReady) Hint("Installs this skill's tools into $distroLabel (the active environment). This can take a few minutes.")
    }
}

@Composable
private fun ImportSkillDialog(onDismiss: () -> Unit, onImport: (String) -> Result<Skill>) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste a SKILL.md (a --- front-matter block with name: and description:, then the instructions).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null },
                    label = { Text("SKILL.md") }, modifier = Modifier.fillMaxWidth(), minLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall.mono()
                )
                error?.let { Callout(it, danger = true) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onImport(text).onSuccess { onDismiss() }.onFailure { error = it.message }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
