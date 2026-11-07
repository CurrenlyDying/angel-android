package com.bruh.angel.ui.project

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bruh.angel.R
import com.bruh.angel.linuxenv.ProjectWorkspace.StatusEntry
import com.bruh.angel.model.Project
import com.bruh.angel.ui.chat.ChatViewModel
import com.bruh.angel.ui.components.Callout
import com.bruh.angel.ui.components.EmptyState
import com.bruh.angel.ui.components.ExternalCodeConsentDialog
import com.bruh.angel.ui.components.Hint
import com.bruh.angel.ui.components.Pill
import com.bruh.angel.ui.theme.AngelTheme
import com.bruh.angel.ui.theme.mono
import kotlinx.coroutines.launch

/** Git working-tree changes for [project]: status, per-file diff, stage/unstage/discard and commit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesScreen(vm: ChatViewModel, project: Project?, onBack: () -> Unit) {
    if (project == null) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing, containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = { TopAppBar(title = { Text("Changes") }, navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), "Back") } }) }) { p ->
            Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) {
                EmptyState(R.drawable.ic_code, "No project", "Open a project to see its changes.")
            }
        }
        return
    }

    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<StatusEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by rememberSaveable(project.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var diffFor by remember { mutableStateOf<StatusEntry?>(null) }
    var confirmDiscard by remember { mutableStateOf<StatusEntry?>(null) }
    var confirmGitInstall by rememberSaveable(project.id) { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            vm.gitStatus(project)
                .onSuccess { entries = it; error = null }
                .onFailure { error = it.message ?: "Could not read status"; entries = emptyList() }
            loading = false
        }
    }
    LaunchedEffect(project.id) { refresh() }

    fun act(block: suspend () -> Result<String>, failMsg: String) {
        scope.launch {
            busy = true
            block().onFailure { vm.notice.value = it.message ?: failMsg }
            busy = false
            refresh()
        }
    }

    val staged = entries.filter { it.staged }
    val unstaged = entries.filter { !it.staged && !it.untracked }
    val untracked = entries.filter { it.untracked }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Column { Text("Changes"); Text(project.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), "Back") } },
                actions = { IconButton(onClick = { refresh() }) { Icon(painterResource(R.drawable.ic_history), "Refresh") } }
            )
        },
        bottomBar = {
            if (staged.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().widthIn(max = 760.dp).navigationBarsPadding().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = message, onValueChange = { message = it.take(500) }, singleLine = true,
                        placeholder = { Text("Commit message") }, modifier = Modifier.weight(1f))
                    Button(enabled = !busy && message.isNotBlank(), onClick = {
                        val msg = message
                        act({ vm.gitCommit(project, msg).onSuccess {
                            if (message == msg) message = ""
                        } }, "Commit failed")
                    }) { Text("Commit") }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            when {
                loading && entries.isEmpty() -> CircularProgressIndicator(Modifier.padding(32.dp))
                error != null -> {
                    val notRepo = error!!.contains("not a git repository", ignoreCase = true)
                    val gitMissing = error!!.contains("not found", ignoreCase = true) || error!!.contains("No such file", ignoreCase = true)
                    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Callout(error!!, danger = true)
                        Hint(
                            when {
                                notRepo -> "${project.workdir} isn't a git repository yet. Initialize one here, or clone/open a repo in it from the Terminal."
                                gitMissing -> "Git isn't installed in ${project.distro.label}. Installing it can take a few minutes."
                                else -> "Something went wrong reading the repository."
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                notRepo -> OutlinedButton(enabled = !busy, onClick = { act({ vm.gitInit(project) }, "Could not initialize repository") }) { Text("Initialize repository") }
                                else -> OutlinedButton(enabled = !busy, onClick = { confirmGitInstall = true }) { Text("Install git") }
                            }
                            OutlinedButton(enabled = !busy, onClick = { refresh() }) { Text("Retry") }
                        }
                        if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Working…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                entries.isEmpty() -> EmptyState(R.drawable.ic_code, "No changes", "The working tree is clean.")
                else -> LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    section("Staged", staged)
                    items(staged, key = { "s-" + it.path }) { e ->
                        FileRow(e, onView = { diffFor = e }, actions = {
                            OutlinedButton(enabled = !busy, onClick = { act({ vm.gitUnstage(project, e.path, e.originalPath) }, "Unstage failed") }) { Text("Unstage") }
                        })
                    }
                    section("Changes", unstaged)
                    items(unstaged, key = { "u-" + it.path }) { e ->
                        FileRow(e, onView = { diffFor = e }, actions = {
                            OutlinedButton(enabled = !busy, onClick = { act({ vm.gitStage(project, e.path) }, "Stage failed") }) { Text("Stage") }
                            TextButton(enabled = !busy, onClick = { confirmDiscard = e }) { Text("Discard", color = AngelTheme.colors.danger) }
                        })
                    }
                    section("Untracked", untracked)
                    items(untracked, key = { "n-" + it.path }) { e ->
                        FileRow(e, onView = { diffFor = e }, actions = {
                            OutlinedButton(enabled = !busy, onClick = { act({ vm.gitStage(project, e.path) }, "Stage failed") }) { Text("Stage") }
                            TextButton(enabled = !busy, onClick = { confirmDiscard = e }) { Text("Delete", color = AngelTheme.colors.danger) }
                        })
                    }
                }
            }
        }
    }

    if (confirmGitInstall) ExternalCodeConsentDialog(
        title = "Install Git?",
        details = "Downloads and installs Git and its dependencies from ${project.distro.label}'s package repositories into this project's Linux environment.",
        onDismiss = { confirmGitInstall = false },
        onConfirm = { confirmGitInstall = false; act({ vm.installGit(project) }, "Could not install git") }
    )

    diffFor?.let { e -> DiffSheet(vm, project, e, onDismiss = { diffFor = null }) }

    confirmDiscard?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text(if (e.untracked) "Delete file?" else "Discard changes?") },
            text = { Text(if (e.untracked) "${e.path} will be permanently deleted." else "Changes to ${e.path} will be lost. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = null
                    act({ vm.gitDiscard(project, e.path, e.untracked) }, "Discard failed")
                }) { Text(if (e.untracked) "Delete" else "Discard", color = AngelTheme.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("Cancel") } }
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, list: List<StatusEntry>) {
    if (list.isNotEmpty()) item(key = "h-$title") {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp))
    }
}

@Composable
private fun FileRow(e: StatusEntry, onView: () -> Unit, actions: @Composable () -> Unit) {
    Surface(onClick = onView, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(e.code.trim().ifEmpty { "?" }, mono = true)
            Spacer(Modifier.size(8.dp))
            Text(e.path, style = MaterialTheme.typography.bodyMedium.mono(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            actions()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffSheet(vm: ChatViewModel, project: Project, e: StatusEntry, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(e.path, e.staged) {
        vm.gitDiff(project, e.path, e.staged, e.untracked, e.originalPath)
            .onSuccess { text = it.ifBlank { "(no textual diff)" } }
            .onFailure { text = it.message ?: "Could not load diff" }
    }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 760.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(e.path, style = MaterialTheme.typography.titleMedium.mono(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val body = text
            if (body == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()).padding(10.dp)) {
                    body.lineSequence().forEach { line ->
                        val color = when {
                            line.startsWith("+") && !line.startsWith("+++") -> AngelTheme.colors.success
                            line.startsWith("-") && !line.startsWith("---") -> AngelTheme.colors.danger
                            line.startsWith("@@") -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(line.ifEmpty { " " }, style = MaterialTheme.typography.labelSmall.mono(), color = color, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}
