package com.bruh.angel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bruh.angel.ui.theme.AngelTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bruh.angel.local.LocalEngine
import com.bruh.angel.ui.chat.AgentStatus
import com.bruh.angel.ui.chat.ApprovalSheet
import com.bruh.angel.ui.chat.ChatScreen
import com.bruh.angel.ui.chat.ChatViewModel
import com.bruh.angel.ui.mcp.McpScreen
import com.bruh.angel.ui.models.LocalModelsScreen
import com.bruh.angel.ui.project.ChangesScreen
import com.bruh.angel.ui.project.ProjectScreen
import com.bruh.angel.ui.project.ProjectsScreen
import com.bruh.angel.ui.skills.SkillsScreen
import com.bruh.angel.ui.terminal.TerminalScreen

enum class Screen { CHAT, TERMINAL, SETTINGS, HISTORY, MODELS, MCP, APPEARANCE, PROJECTS, PROJECT, CHANGES, SKILLS }

@Composable
fun AngelApp(vm: ChatViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    val reduceMotion = AngelTheme.preferences.reduceMotion
    val items by vm.items.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val installing by vm.installing.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val profile by vm.settings.collectAsStateWithLifecycle()
    val localModels by vm.localModels.models.collectAsStateWithLifecycle()
    val engine by LocalEngine.state.collectAsStateWithLifecycle()
    val shizuku by vm.bridge.status.collectAsStateWithLifecycle()
    val linux by vm.linuxStatus.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val activeProject by vm.activeProject.collectAsStateWithLifecycle()
    val activeDistro by vm.activeDistro.collectAsStateWithLifecycle()
    var editingProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var changesProjectId by rememberSaveable { mutableStateOf<String?>(null) }

    val activeLocal = localModels.firstOrNull { it.id == profile.model }
    val modelLabel = if (profile.provider.isLocal) {
        val name = activeLocal?.name ?: "no model selected"
        if (engine is LocalEngine.State.Loading) "On-device · loading $name…" else "On-device · $name"
    } else {
        "${profile.provider.label} · ${profile.model}" + if (profile.apiKey.isBlank()) " · no API key" else ""
    }
    val status = AgentStatus(
        modelLabel = modelLabel,
        providerReady = if (profile.provider.isLocal) activeLocal?.ready == true else profile.apiKey.isNotBlank(),
        shizukuConnected = shizuku.startsWith("Connected"),
        linuxReady = linux.startsWith("Ready"),
        local = profile.provider.isLocal,
        distro = activeDistro
    )

    // One-line notices (saved, failed, …) surface as snackbars on whichever screen is open.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        if (notice.isNotEmpty()) {
            snackbar.showSnackbar(notice, withDismissAction = true)
            if (vm.notice.value == notice) vm.notice.value = ""
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                if (reduceMotion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 40 }) togetherWith fadeOut(tween(120))
            },
            label = "screen"
        ) { target ->
            when (target) {
                Screen.CHAT -> ChatScreen(
                    items = items,
                    onSend = vm::send,
                    busy = busy || installing,
                    activity = activity,
                    awaitingApproval = pending != null,
                    status = status,
                    onSettings = { screen = Screen.SETTINGS },
                    onStop = vm::stop,
                    onKillCommand = vm::killCommand,
                    onNewChat = vm::newChat,
                    onHistory = { vm.refreshHistory(); screen = Screen.HISTORY },
                    onTerminal = { screen = Screen.TERMINAL },
                    onProjects = { vm.refreshProjects(); vm.refreshHistory(); screen = Screen.PROJECTS },
                    activeProjectName = activeProject?.name
                )
                Screen.TERMINAL -> TerminalScreen(onBack = { screen = Screen.CHAT }, distro = activeDistro, workdir = activeProject?.workdir.orEmpty())
                Screen.SETTINGS -> SettingsScreen(
                    vm,
                    onBack = { screen = Screen.CHAT },
                    onOpenTerminal = { screen = Screen.TERMINAL },
                    onOpenModels = { screen = Screen.MODELS },
                    onOpenMcp = { screen = Screen.MCP },
                    onOpenAppearance = { screen = Screen.APPEARANCE },
                    onOpenSkills = { vm.refreshSkills(); screen = Screen.SKILLS }
                )
                Screen.APPEARANCE -> AppearanceScreen(onBack = { screen = Screen.SETTINGS })
                Screen.HISTORY -> HistoryScreen(vm, onBack = { screen = Screen.CHAT }, onOpen = { vm.openConversation(it); screen = Screen.CHAT })
                Screen.MCP -> McpScreen(vm, onBack = { screen = Screen.SETTINGS })
                Screen.MODELS -> LocalModelsScreen(
                    activeModelId = profile.model.takeIf { profile.provider.isLocal },
                    onUse = vm::useLocalModel,
                    onBack = { screen = Screen.SETTINGS }
                )
                Screen.PROJECTS -> ProjectsScreen(
                    vm,
                    onBack = { screen = Screen.CHAT },
                    onNew = { editingProjectId = null; screen = Screen.PROJECT },
                    onOpenProject = { editingProjectId = it; screen = Screen.PROJECT },
                    onOpenGeneral = { vm.setActiveProject(null); screen = Screen.CHAT },
                    onNewSkillProject = { vm.newSkillProject(it); screen = Screen.CHAT }
                )
                Screen.SKILLS -> SkillsScreen(vm, onBack = { screen = Screen.SETTINGS })
                Screen.PROJECT -> ProjectScreen(
                    vm,
                    existing = projects.firstOrNull { it.id == editingProjectId },
                    onBack = { screen = Screen.PROJECTS },
                    onOpenChat = { screen = Screen.CHAT },
                    onOpenChanges = { changesProjectId = it; screen = Screen.CHANGES }
                )
                Screen.CHANGES -> ChangesScreen(
                    vm,
                    project = projects.firstOrNull { it.id == changesProjectId },
                    onBack = { screen = Screen.PROJECT }
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(bottom = if (screen == Screen.CHAT) 68.dp else 0.dp)
        )
    }
    // Settings handles Back itself (to offer saving unsaved changes); Local models returns to Settings.
    BackHandler(enabled = screen == Screen.TERMINAL || screen == Screen.HISTORY || screen == Screen.PROJECTS) { screen = Screen.CHAT }
    BackHandler(enabled = screen == Screen.MODELS || screen == Screen.MCP || screen == Screen.SKILLS) { screen = Screen.SETTINGS }
    BackHandler(enabled = screen == Screen.PROJECT) { screen = Screen.PROJECTS }
    BackHandler(enabled = screen == Screen.CHANGES) { screen = Screen.PROJECT }

    pending?.let { tool -> ApprovalSheet(tool, profile.provider, activeDistro, onDecision = vm::approve, mcpLabel = vm.mcp.label(tool.name)) }
}
