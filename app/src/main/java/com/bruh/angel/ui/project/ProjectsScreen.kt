package com.bruh.angel.ui.project

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bruh.angel.R
import com.bruh.angel.model.Project
import com.bruh.angel.model.SkillCatalog
import com.bruh.angel.ui.chat.ChatViewModel
import com.bruh.angel.ui.components.Pill
import com.bruh.angel.ui.components.ExternalCodeConsentDialog
import com.bruh.angel.ui.components.angelTopBarColors
import com.bruh.angel.ui.theme.mono

/** Lists projects plus a "General" bucket for unassigned chats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenGeneral: () -> Unit,
    onNewSkillProject: (String) -> Unit
) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val active by vm.activeProject.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val skills by vm.skills.collectAsStateWithLifecycle()
    var confirmTemplate by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                colors = angelTopBarColors(),
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") } },
                actions = { IconButton(onClick = onNew) { Icon(painterResource(R.drawable.ic_add), contentDescription = "New project") } }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "general") {
                    ProjectRow(
                        title = "General",
                        subtitle = "Chats not in a project",
                        chats = history.count { it.projectId == null },
                        folder = null,
                        activeNow = active == null,
                        onClick = onOpenGeneral
                    )
                }
                items(projects, key = { it.id }) { p ->
                    ProjectRow(
                        title = p.name,
                        subtitle = p.workdir,
                        chats = history.count { it.projectId == p.id },
                        folder = p.distro.label,
                        activeNow = active?.id == p.id,
                        subtitleMono = true,
                        onClick = { onOpenProject(p.id) }
                    )
                }
                val templates = skills.filter { it.builtin && it.enabled }
                if (templates.isNotEmpty()) {
                    item(key = "templates-header") {
                        Text("Start from a skill", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                    }
                    items(templates, key = { "tpl-${it.id}" }) { skill ->
                        TemplateRow(title = "New ${skill.name} project", subtitle = skill.description, onClick = { confirmTemplate = skill.id })
                    }
                }
                item(key = "hint") {
                    Text(
                        "A project gives the agent a fixed working directory, environment, instructions and MCP connectors, and keeps its chats together.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
    skills.firstOrNull { it.id == confirmTemplate }?.let { skill ->
        ExternalCodeConsentDialog(
            title = "Create ${skill.name} project?",
            details = if (skill.id == SkillCatalog.REVERSE_ENGINEERING)
                "Creates a Kali project, downloads its analysis tools from Kali's package repositories, and builds the pinned radare2 MCP connector from github.com/radareorg/radare2-mcp. These tools can access Linux files and /sdcard."
            else "Creates a project and installs this skill's command-line tools and MCP connectors in its Linux environment, using the sources selected by its setup script.",
            onDismiss = { confirmTemplate = null },
            onConfirm = { confirmTemplate = null; onNewSkillProject(skill.id) },
            confirmLabel = "Create and install"
        )
    }
}

@Composable
private fun TemplateRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(R.drawable.ic_add), null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ProjectRow(
    title: String,
    subtitle: String,
    chats: Int,
    folder: String?,
    activeNow: Boolean,
    onClick: () -> Unit,
    subtitleMono: Boolean = false
) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_folder_open), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (activeNow) Pill("active", color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    subtitle,
                    style = if (subtitleMono) MaterialTheme.typography.bodySmall.mono() else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("$chats chats")
                    if (folder != null) Pill(folder)
                }
            }
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}
