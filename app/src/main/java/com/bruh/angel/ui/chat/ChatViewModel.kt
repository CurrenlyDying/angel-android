package com.bruh.angel.ui.chat

import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bruh.angel.agent.ChatModel
import com.bruh.angel.agent.LocalModelSession
import com.bruh.angel.agent.ModelSession
import com.bruh.angel.agent.ProviderApi
import com.bruh.angel.agent.RequestedTool
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.linuxenv.LinuxDownloader
import com.bruh.angel.linuxenv.ProjectWorkspace
import com.bruh.angel.mcp.McpManager
import com.bruh.angel.local.HuggingFace
import com.bruh.angel.local.LocalEngine
import com.bruh.angel.local.LocalModelStore
import com.bruh.angel.model.AgentPrefs
import com.bruh.angel.model.ChatItem
import com.bruh.angel.model.Conversation
import com.bruh.angel.model.ConversationMeta
import com.bruh.angel.model.ConversationStore
import com.bruh.angel.model.Project
import com.bruh.angel.model.Skill
import com.bruh.angel.model.SkillCatalog
import com.bruh.angel.model.Provider
import com.bruh.angel.model.ProviderSettings
import com.bruh.angel.model.ProviderSettingsStore
import com.bruh.angel.model.ToolNames
import com.bruh.angel.model.ToolPolicy
import com.bruh.angel.shizuku.ShizukuBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

private class ToolRequestDenied(val blocked: Boolean = false) : Exception()

/**
 * Distro and working directory a request's shell commands run in. Captured when the request
 * starts so that editing the active project while a request is in flight (for example while a
 * tool awaits approval) cannot redirect that request's commands into the new directory/distro.
 */
private data class RequestContext(val distro: LinuxDistro, val workdir: String, val project: Project?)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ProviderSettingsStore(application)
    private val conversations = ConversationStore(application)
    val bridge = ShizukuBridge.shared(application)
    private val downloader = LinuxDownloader(application, bridge)
    val projects = MutableStateFlow<List<Project>>(emptyList())
    val activeProject = MutableStateFlow<Project?>(null)
    /** Available skills: built-ins (bodies from assets) overlaid with saved state, plus imported ones. */
    val skills = MutableStateFlow<List<Skill>>(emptyList())
    @Volatile private var runningContext: RequestContext? = null
    private fun agentProject(): Project? {
        val context = runningContext
        return if (context == null) activeProject.value else context.project
    }
    val mcp = McpManager(bridge, store, viewModelScope,
        { runningContext?.distro ?: activeDistroValue() }, { agentProject()?.connectors })

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items = _items.asStateFlow()
    val settings = MutableStateFlow(ProviderSettings(Provider.OPENAI, Provider.OPENAI.defaultModel, ""))
    val prefs = MutableStateFlow(AgentPrefs())
    val notice = MutableStateFlow("")
    val busy = MutableStateFlow(true) // until saved settings are loaded
    val activity = MutableStateFlow("Thinking…")
    val installing = MutableStateFlow(false)
    /** Status of the selected distribution: "Ready: ...", "Not installed", or a progress / error line. */
    val linuxStatus = MutableStateFlow("Connect Shizuku to check Linux")
    val installedDistros = MutableStateFlow<Set<LinuxDistro>>(emptySet())
    val installingDistro = MutableStateFlow<LinuxDistro?>(null)
    val installProgress = MutableStateFlow<Float?>(null)
    val pending = MutableStateFlow<RequestedTool?>(null)
    val history = MutableStateFlow<List<ConversationMeta>>(emptyList())

    /** Distro the agent and terminal use now: the active project's, else the global selection. */
    val activeDistro: StateFlow<LinuxDistro> = combine(activeProject, prefs) { p, pr -> p?.distro ?: pr.distro }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LinuxDistro.DEFAULT)
    private fun activeDistroValue(): LinuxDistro = activeProject.value?.distro ?: prefs.value.distro
    /** Project the open conversation belongs to; fixed when the chat is created or opened. */
    private var conversationProjectId: String? = null

    // Settings drafts live in the ViewModel so typing survives fold/unfold and navigation.
    val draftModel = MutableStateFlow("")
    val draftKey = MutableStateFlow("")
    val models = MutableStateFlow<List<String>>(emptyList())
    val modelStatus = MutableStateFlow("")
    val checkingModels = MutableStateFlow(false)
    val draftDirty: StateFlow<Boolean> = combine(draftModel, draftKey, settings) { model, key, saved ->
        model.trim() != saved.model || key.trim() != saved.apiKey
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The user's answer to an approval prompt. [command] may have been edited. */
    data class Approval(val run: Boolean, val command: String, val always: Boolean)

    private var approval: CompletableDeferred<Approval>? = null
    private var session: ChatModel? = null
    val localModels = LocalModelStore.get(application)
    private var job: Job? = null
    private var mcpCall: kotlinx.coroutines.Deferred<*>? = null
    private var runningIndex: Int? = null
    @Volatile private var killRequested = false
    private var conversationId = ConversationStore.newId()

    init {
        mcp.load()
        viewModelScope.launch {
            try {
                val (loaded, loadedPrefs) = withContext(Dispatchers.IO) { store.load(store.selected()) to store.loadPrefs() }
                settings.value = loaded
                prefs.value = loadedPrefs
                resetDrafts()
                if (bridge.status.value.startsWith("Connected")) refreshLinux()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = "Could not read saved settings (${e.message}). Re-enter them in Settings."
            } finally {
                busy.value = false
            }
            refreshProjects()
            refreshSkills()
            refreshHistory()
        }
        viewModelScope.launch {
            bridge.status.collect { if (it.startsWith("Connected")) refreshLinux() }
        }
        viewModelScope.launch { runCatching { HuggingFace.reconcile(application) } }
    }

    // ---- Settings ----------------------------------------------------------------------------

    fun resetDrafts() {
        draftModel.value = settings.value.model
        draftKey.value = settings.value.apiKey
    }

    fun selectProvider(provider: Provider) {
        if (busy.value || installing.value || provider == settings.value.provider) return
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    store.select(provider) // persisted immediately: survives restarts
                    store.load(provider)
                }
                applyProfile(loaded)
                models.value = emptyList()
                modelStatus.value = if (loaded.apiKey.isBlank()) "No API key saved for ${provider.label} yet." else ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = "Could not switch provider: ${e.message}"
            }
        }
    }

    private fun applyProfile(value: ProviderSettings) {
        val previous = settings.value
        settings.value = value
        resetDrafts()
        // A different model can't reuse provider-native context; the visible chat is carried over as a transcript.
        if (previous.provider != value.provider || previous.model != value.model || previous.apiKey != value.apiKey) session = null
        if (previous.provider.isLocal && !value.provider.isLocal) viewModelScope.launch { LocalEngine.unload() }
    }

    fun saveDraft(onSaved: () -> Unit = {}) {
        if (busy.value || installing.value) return
        val value = settings.value.copy(model = draftModel.value.trim(), apiKey = draftKey.value.trim())
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(value) }
                applyProfile(value)
                notice.value = "Saved ${value.provider.label} · ${value.model}."
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = e.message ?: "Could not save settings."
            }
        }
    }

    /** Fixes settings typed under the wrong provider: saves the draft model+key under [target] and selects it. */
    fun moveDraftTo(target: Provider) {
        if (busy.value || installing.value) return
        val value = ProviderSettings(target, draftModel.value.trim(), draftKey.value.trim())
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(value) } // also persists target as selected
                applyProfile(value)
                models.value = emptyList()
                modelStatus.value = ""
                notice.value = "Saved ${value.model} under ${target.label} and switched to it."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = e.message ?: "Could not save settings."
            }
        }
    }

    /** Lists models with the (possibly unsaved) draft key: a free connection test. */
    fun fetchModels() {
        if (checkingModels.value) return
        val probe = settings.value.copy(model = draftModel.value.trim(), apiKey = draftKey.value.trim())
        if (probe.apiKey.isBlank()) {
            modelStatus.value = "Enter an API key first."
            return
        }
        checkingModels.value = true
        modelStatus.value = "Contacting ${probe.provider.label}…"
        viewModelScope.launch {
            try {
                val list = ProviderApi.listModels(probe)
                models.value = list
                modelStatus.value = when {
                    list.isEmpty() -> "Key works, but the provider listed no chat models."
                    probe.model in list -> "✓ Key works and ${probe.model} is available (${list.size} models)."
                    else -> "Key works, but '${probe.model}' is not among your ${list.size} models. Choose one below."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                modelStatus.value = e.message ?: "Request failed."
            } finally {
                checkingModels.value = false
            }
        }
    }

    fun savePrefs(value: AgentPrefs) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.savePrefs(value) }
                if (value.instructions != prefs.value.instructions || value.distro != prefs.value.distro) session = null
                prefs.value = value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = e.message ?: "Could not save agent settings."
            }
        }
    }

    fun setPolicy(tool: String, policy: ToolPolicy) = savePrefs(prefs.value.copy(policies = prefs.value.policies + (tool to policy)))

    // ---- Shizuku / Linux ---------------------------------------------------------------------

    fun refreshLinux() {
        if (installing.value) return
        viewModelScope.launch {
            val distro = activeDistroValue()
            try {
                val (status, installed) = withContext(Dispatchers.IO) {
                    val api = bridge.api()
                    api.linuxStatus(distro.id) to api.linuxInstalled().split(',').mapNotNull { id -> LinuxDistro.entries.firstOrNull { it.id == id } }.toSet()
                }
                if (installing.value) return@launch
                linuxStatus.value = status
                installedDistros.value = installed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                linuxStatus.value = e.message ?: "Linux unavailable"
            }
        }
    }

    /** Makes [distro] the one the agent and the Linux terminal use. */
    fun selectDistro(distro: LinuxDistro) {
        if (distro == prefs.value.distro || busy.value) return
        savePrefs(prefs.value.copy(distro = distro))
        linuxStatus.value = "Checking ${distro.label}…"
        viewModelScope.launch {
            // savePrefs updates prefs asynchronously; wait for it before reading the distro back.
            prefs.first { it.distro == distro }
            refreshLinux()
        }
    }

    fun installLinux(distro: LinuxDistro = prefs.value.distro) {
        if (installing.value || busy.value) return
        installing.value = true
        installingDistro.value = distro
        installProgress.value = null
        viewModelScope.launch {
            val selected = distro == prefs.value.distro
            try {
                val result = downloader.install(distro) { text, fraction ->
                    if (selected) linuxStatus.value = text
                    installProgress.value = fraction
                }
                if (selected) linuxStatus.value = result
                installedDistros.value = installedDistros.value + distro
                if (selected) session = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (selected) linuxStatus.value = "Install failed: ${e.message}"
                else notice.value = "${distro.label} install failed: ${e.message}"
            } finally {
                installing.value = false
                installingDistro.value = null
                installProgress.value = null
            }
        }
    }

    fun removeLinux(distro: LinuxDistro) {
        if (installing.value || busy.value) return
        viewModelScope.launch {
            try {
                mcp.distroRemoved(distro)
                withContext(Dispatchers.IO) { bridge.api().removeLinux(distro.id) }
                installedDistros.value = installedDistros.value - distro
                if (distro == prefs.value.distro) { linuxStatus.value = "Not installed"; session = null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = "Could not remove ${distro.label}: ${e.message}"
            }
        }
    }

    fun smokeTest(linux: Boolean) {
        if (busy.value || installing.value) return
        launchWork {
            val tool = RequestedTool("manual", if (linux) ToolNames.LINUX else ToolNames.ANDROID,
                if (linux) "uname -m; head -n 2 /etc/os-release; id" else "id")
            execute(tool, tool.command, RequestContext(activeDistroValue(), activeProject.value?.workdir.orEmpty(), activeProject.value))
        }
    }

    // ---- Conversations -----------------------------------------------------------------------

    fun refreshHistory() {
        viewModelScope.launch {
            history.value = withContext(Dispatchers.IO) { runCatching { conversations.list() }.getOrDefault(emptyList()) }
        }
    }

    private fun snapshot(): Conversation? {
        val current = _items.value
        val first = current.firstOrNull { it is ChatItem.UserMsg } as? ChatItem.UserMsg ?: return null
        return Conversation(
            id = conversationId,
            title = first.text.lineSequence().first().take(80),
            updated = System.currentTimeMillis(),
            provider = settings.value.provider,
            model = settings.value.model,
            items = current,
            history = session?.exportHistory(),
            projectId = conversationProjectId
        )
    }

    private fun persist() {
        val snap = snapshot() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { conversations.save(snap) }
            history.value = runCatching { conversations.list() }.getOrDefault(history.value)
        }
    }

    fun newChat() {
        if (busy.value) return
        persist()
        conversationId = ConversationStore.newId()
        conversationProjectId = activeProject.value?.id
        session = null
        _items.value = emptyList()
    }

    fun openConversation(id: String) {
        if (busy.value) return
        persist()
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { conversations.load(id) }
            if (loaded == null) {
                notice.value = "That conversation could not be opened."
                return@launch
            }
            conversationId = loaded.id
            conversationProjectId = loaded.projectId
            // Opening a project's chat makes that project active (its distro, workdir and connectors).
            val targetProject = loaded.projectId?.let { pid -> projects.value.firstOrNull { it.id == pid } }
            if (targetProject?.id != activeProject.value?.id) {
                activeProject.value = targetProject
                refreshLinux()
            }
            _items.value = loaded.items.map {
                if (it is ChatItem.ToolCall && it.running) it.copy(running = false, exitCode = 130, output = it.output + "\n[interrupted]") else it
            }
            val active = settings.value
            // Provider-native context is only reusable with the same provider and model.
            session = if (loaded.history != null && loaded.provider == active.provider && loaded.model == active.model)
                runCatching { createSession(loaded.history) }.getOrNull() else null
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { conversations.delete(id) }
            history.value = runCatching { conversations.list() }.getOrDefault(emptyList())
        }
        if (id == conversationId && !busy.value) {
            conversationId = ConversationStore.newId()
            conversationProjectId = activeProject.value?.id
            session = null
            _items.value = emptyList()
        }
    }

    fun deleteAllConversations() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { conversations.deleteAll() }
            history.value = emptyList()
        }
        if (!busy.value) {
            conversationId = ConversationStore.newId()
            conversationProjectId = activeProject.value?.id
            session = null
            _items.value = emptyList()
        }
    }

    suspend fun transcript(id: String): String? = withContext(Dispatchers.IO) {
        conversations.load(id)?.let { ConversationStore.transcript(it) }
    }

    // ---- Projects ----------------------------------------------------------------------------

    fun refreshProjects() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { store.loadProjects() }.getOrDefault(emptyList()) }
                .sortedByDescending { it.updated }
            projects.value = loaded
            activeProject.value?.let { active -> activeProject.value = loaded.firstOrNull { it.id == active.id } }
        }
    }

    private suspend fun persistProjects(list: List<Project>) {
        projects.value = list.sortedByDescending { it.updated }
        withContext(Dispatchers.IO) { runCatching { store.saveProjects(projects.value) } }
    }

    /** Creates a project and makes it active (in a fresh chat). Returns its id. */
    fun createProject(
        name: String, workdir: String, distroId: String,
        instructions: String = "", connectors: Set<String> = defaultConnectors(), repoUrl: String = "",
        skills: Set<String> = emptySet()
    ): String {
        val project = Project(
            id = Project.newId(), name = name.trim().ifBlank { "Project" }.take(60),
            workdir = workdir.trim().ifBlank { "/root" }, distroId = distroId,
            instructions = instructions.trim(), connectors = connectors, skills = skills, repoUrl = repoUrl.trim()
        )
        viewModelScope.launch {
            persistProjects(projects.value.filter { it.id != project.id } + project)
            setActiveProject(project)
        }
        return project.id
    }

    fun updateProject(project: Project) {
        val updated = project.copy(updated = System.currentTimeMillis())
        viewModelScope.launch {
            persistProjects(projects.value.map { if (it.id == updated.id) updated else it })
            if (activeProject.value?.id == updated.id) {
                activeProject.value = updated
                session = null
                if (!settings.value.provider.isLocal) mcp.prepare()
            }
        }
    }

    fun deleteProject(id: String) {
        if (busy.value && activeProject.value?.id == id) {
            notice.value = "Stop the current request before deleting its project."
            return
        }
        viewModelScope.launch {
            // Return this project's chats to "general" so they are not orphaned by the deletion.
            withContext(Dispatchers.IO) {
                conversations.list(id).forEach { meta ->
                    conversations.load(meta.id)?.let { runCatching { conversations.save(it.copy(projectId = null)) } }
                }
            }
            persistProjects(projects.value.filter { it.id != id })
            if (activeProject.value?.id == id) setActiveProject(null)
            refreshHistory()
        }
    }

    /** Switches the active project, starting a fresh chat in its context. */
    fun setActiveProject(project: Project?) {
        if (project?.id == activeProject.value?.id) { activeProject.value = project; return }
        if (busy.value) return
        persist()
        activeProject.value = project
        conversationId = ConversationStore.newId()
        conversationProjectId = project?.id
        session = null
        _items.value = emptyList()
        refreshLinux()
        viewModelScope.launch { if (!settings.value.provider.isLocal) mcp.prepare() }
    }

    /**
     * Explicit "Open in a new chat" from the project editor: always starts a fresh chat in
     * [project], even when it is already active (unlike [setActiveProject], whose same-project
     * early return would keep showing the previous conversation). The old chat is persisted first.
     */
    fun openProjectInNewChat(project: Project) {
        if (busy.value) return
        persist()
        val switching = project.id != activeProject.value?.id
        activeProject.value = project
        conversationId = ConversationStore.newId()
        conversationProjectId = project.id
        session = null
        _items.value = emptyList()
        if (switching) {
            refreshLinux()
            viewModelScope.launch { if (!settings.value.provider.isLocal) mcp.prepare() }
        }
    }

    fun setConnectorEnabled(projectId: String, serverId: String, enabled: Boolean) {
        val project = projects.value.firstOrNull { it.id == projectId } ?: return
        updateProject(project.copy(connectors = if (enabled) project.connectors + serverId else project.connectors - serverId))
    }

    /** Default connectors for a new project: whatever MCP servers are currently enabled. */
    private fun defaultConnectors(): Set<String> =
        mcp.servers.value.filter { it.config.enabled }.map { it.config.id }.toSet()

    // ---- Skills ------------------------------------------------------------------------------

    /** Progress line while a skill's tools/connectors are being set up, or null when idle. */
    val skillStatus = MutableStateFlow<String?>(null)

    fun refreshSkills() {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { runCatching { store.loadSkills() }.getOrDefault(emptyList()) }
            val builtins = withContext(Dispatchers.IO) { runCatching { SkillCatalog.load(getApplication()) }.getOrDefault(emptyList()) }
            val merged = builtins.map { b ->
                val saved = stored.firstOrNull { it.id == b.id && it.builtin }
                if (saved == null) b else b.copy(enabled = saved.enabled, setupDone = saved.setupDone,
                    connectorIds = saved.connectorIds, setupDistros = saved.setupDistros)
            } + stored.filter { s -> !s.builtin && builtins.none { it.id == s.id } }
            skills.value = merged.sortedWith(compareByDescending<Skill> { it.builtin }.thenBy { it.name.lowercase() })
        }
    }

    private suspend fun persistSkills(list: List<Skill>) {
        skills.value = list.sortedWith(compareByDescending<Skill> { it.builtin }.thenBy { it.name.lowercase() })
        withContext(Dispatchers.IO) { runCatching { store.saveSkills(skills.value) } }
    }

    fun setSkillEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { persistSkills(skills.value.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    }

    /** Imports a SKILL.md (pasted or fetched). Returns the new skill, or an error to show the user. */
    fun importSkill(text: String): Result<Skill> {
        val taken = skills.value.map { it.id }.toSet()
        val parsed = Skill.parseSkillMd(text, taken)
            ?: return Result.failure(IllegalArgumentException("Not a valid SKILL.md — it needs a --- front matter block with name: and description:."))
        viewModelScope.launch { persistSkills(skills.value.filter { it.id != parsed.id } + parsed) }
        return Result.success(parsed)
    }

    /** Deletes an imported skill (built-ins cannot be deleted) and detaches it from every project. */
    fun deleteSkill(id: String) {
        val skill = skills.value.firstOrNull { it.id == id } ?: return
        if (skill.builtin) return
        viewModelScope.launch {
            persistSkills(skills.value.filter { it.id != id })
            projects.value.filter { id in it.skills }.forEach { p ->
                updateProject(p.copy(skills = p.skills - id, connectors = p.connectors - skill.connectorIds))
            }
        }
    }

    /** Attaches/detaches a skill to a project; attaching also turns on the skill's installed connectors. */
    fun setSkillAttached(projectId: String, skillId: String, attached: Boolean) {
        val project = projects.value.firstOrNull { it.id == projectId } ?: return
        val skill = skills.value.firstOrNull { it.id == skillId } ?: return
        updateProject(project.copy(
            skills = if (attached) project.skills + skillId else project.skills - skillId,
            connectors = if (attached) project.connectors + skill.connectorIds else project.connectors - skill.connectorIds
        ))
    }

    /** Runs [script] in [distro] via spawn, streaming non-blank lines to [onLine]; true on exit 0. */
    private suspend fun runScript(script: String, distro: LinuxDistro, timeoutMs: Long = 15 * 60_000L, onLine: (String) -> Unit = {}): Boolean =
        bridge.runLinuxScript(script, distro, timeoutMs, onLine) == 0

    /**
     * Installs a skill's CLI tools and MCP connectors into [distro], streaming progress to [skillStatus].
     * Records the installed connector ids on the skill and turns them on for any project it is attached to.
     * Safe to re-run.
     */
    fun setUpSkill(skillId: String, distro: LinuxDistro = activeDistroValue()) {
        val skill = skills.value.firstOrNull { it.id == skillId } ?: return
        if (skillStatus.value != null) return
        viewModelScope.launch {
            skillStatus.value = "Setting up ${skill.name}…"
            try {
                persistSkills(skills.value.map { if (it.id == skillId)
                    it.copy(setupDone = false, setupDistros = it.setupDistros - distro.id) else it })
                SkillCatalog.setupScript(skillId, distro)?.let { script ->
                    val log = StringBuilder()
                    val ok = runScript(script, distro) { line ->
                        log.appendLine(line)
                        skillStatus.value = line.removePrefix("==> ").take(120)
                    }
                    if (!ok) throw IllegalStateException(
                        log.lineSequence().lastOrNull { it.isNotBlank() && !it.startsWith("==> ") }?.take(300)
                            ?: "Tool setup did not finish cleanly"
                    )
                }
                val ids = mutableSetOf<String>()
                for (config in SkillCatalog.connectors(skillId, emptySet(), distro)) {
                    val currentSkill = skills.value.first { it.id == skillId }
                    val existing = SkillCatalog.reusableConnector(currentSkill, config, distro, mcp.servers.value.map { it.config })
                    val id = existing?.id ?: mcp.add(config.copy(id = com.bruh.angel.mcp.McpServerConfig.slug(config.id, mcp.taken()))).id
                    ids += id
                    // Keep failed attempts associated with the skill so the next setup retries them.
                    persistSkills(skills.value.map { if (it.id == skillId) it.copy(connectorIds = it.connectorIds + id) else it })
                    skillStatus.value = "Installing ${config.name}…"
                    mcp.installAndAwait(id, distro)
                }
                persistSkills(skills.value.map { if (it.id == skillId) it.copy(setupDone = true,
                    setupDistros = it.setupDistros + distro.id, connectorIds = it.connectorIds + ids) else it })
                projects.value.filter { skillId in it.skills && it.distro == distro }.forEach { p -> updateProject(p.copy(connectors = p.connectors + ids)) }
                notice.value = "${skill.name} is ready"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = "Skill setup failed: ${e.message}"
            } finally {
                skillStatus.value = null
            }
        }
    }

    /** One-tap template: creates a project pre-set for [skillId] with the skill attached, then sets it up. */
    fun newSkillProject(skillId: String) {
        val skill = skills.value.firstOrNull { it.id == skillId } ?: return
        // apktool/jadx want apt + a JVM, and Kali ships the richest RE packages, so default there.
        val distro = if (skillId == SkillCatalog.REVERSE_ENGINEERING) LinuxDistro.KALI else activeDistroValue()
        val workdir = if (skillId == SkillCatalog.REVERSE_ENGINEERING) "/root/re" else "/root"
        viewModelScope.launch {
            runCatching { bridge.execute("mkdir -p -- ${shellQuote(workdir)}", true, distro) }
            createProject(name = skill.name, workdir = workdir, distroId = distro.id, connectors = emptySet(), skills = setOf(skillId))
            if (SkillCatalog.needsSetup(skillId, distro)) setUpSkill(skillId, distro)
        }
    }

    data class DirEntry(val name: String, val isDir: Boolean)

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    /** Lists directory entries under [path] in the given distro, for the directory browser. */
    suspend fun listDirectory(path: String, distro: LinuxDistro = activeDistroValue()): Result<List<DirEntry>> = runCatching {
        val safe = if (path.startsWith("/")) path else "/root"
        val result = bridge.execute("ls -1Ap -- ${shellQuote(safe)}", true, distro)
        val out = result.getString("output").orEmpty()
        if (result.getInt("exitCode") != 0) throw IllegalStateException(out.lineSequence().firstOrNull().orEmpty().ifBlank { "Could not open $safe" })
        out.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() && it != "./" && it != "../" }
            .map { if (it.endsWith("/")) DirEntry(it.dropLast(1), true) else DirEntry(it, false) }
            .sortedWith(compareByDescending<DirEntry> { it.isDir }.thenBy { it.name.lowercase() })
            .toList()
    }

    suspend fun makeDirectory(parent: String, name: String, distro: LinuxDistro = activeDistroValue()): Result<String> = runCatching {
        require(name.isNotBlank() && '/' !in name && name != "." && name != "..") { "Enter a valid folder name" }
        val path = parent.trimEnd('/') + "/" + name.trim()
        val result = bridge.execute("mkdir -p -- ${shellQuote(path)}", true, distro)
        check(result.getInt("exitCode") == 0) {
            result.getString("output").orEmpty().lineSequence().firstOrNull().orEmpty().ifBlank { "Could not create folder" }
        }
        path
    }

    // ---- Repo / Git (Changes) ----------------------------------------------------------------

    /** Progress line while a project's repo is being cloned, or null when idle. */
    val cloneStatus = MutableStateFlow<String?>(null)

    /** Clones a project's repo into its working directory, streaming progress to [cloneStatus]. */
    fun cloneRepo(projectId: String) {
        val project = projects.value.firstOrNull { it.id == projectId } ?: return
        ProjectWorkspace.validateRepoUrl(project.repoUrl)?.let { notice.value = it; return }
        viewModelScope.launch {
            cloneStatus.value = "Cloning ${project.repoUrl}…"
            try {
                val script = ProjectWorkspace.cloneScript(project.repoUrl, project.workdir, project.distro.aptBased)
                val exit = bridge.runLinuxScript(script, project.distro, 10 * 60_000L) { line ->
                    cloneStatus.value = line.removePrefix("==> ").take(120)
                }
                notice.value = if (exit == 0) "Cloned into ${project.workdir}"
                    else "Clone failed (exit ${exit ?: "?"}). Check the URL and that the folder is empty."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = "Clone failed: ${e.message}"
            } finally {
                cloneStatus.value = null
            }
        }
    }

    private suspend fun git(project: Project, command: String): Pair<Int, String> {
        val result = bridge.execute(command, true, project.distro, project.workdir)
        return result.getInt("exitCode") to result.getString("output").orEmpty()
    }

    suspend fun gitStatus(project: Project): Result<List<ProjectWorkspace.StatusEntry>> = runCatching {
        val (exit, out) = git(project, ProjectWorkspace.STATUS)
        if (exit != 0) throw IllegalStateException(out.lineSequence().firstOrNull().orEmpty().ifBlank { "Not a git repository" })
        ProjectWorkspace.parseStatus(out)
    }

    suspend fun gitDiff(project: Project, path: String, staged: Boolean, untracked: Boolean, originalPath: String? = null): Result<String> = runCatching {
        git(project, ProjectWorkspace.diff(path, staged, untracked, originalPath)).second
    }

    suspend fun gitStage(project: Project, path: String) = gitRun(project, ProjectWorkspace.stage(path))
    suspend fun gitUnstage(project: Project, path: String, originalPath: String? = null) = gitRun(project, ProjectWorkspace.unstage(path, originalPath))
    suspend fun gitDiscard(project: Project, path: String, untracked: Boolean) = gitRun(project, ProjectWorkspace.discard(path, untracked))
    suspend fun gitCommit(project: Project, message: String) = gitRun(project, ProjectWorkspace.commit(message))
    suspend fun gitInit(project: Project) = gitRun(project, "git init 2>&1")

    /** Installs git into the project's distro. Uses spawn (not the 60s tool path) since apt can take minutes. */
    suspend fun installGit(project: Project): Result<String> = runCatching {
        val log = StringBuilder()
        val exit: Int?
        try {
            exit = bridge.runLinuxScript(ProjectWorkspace.ensureGitScript(project.distro.aptBased), project.distro, 10 * 60_000L) { line ->
                log.appendLine(line)
                cloneStatus.value = line.removePrefix("==> ").take(120)
            }
        } finally {
            cloneStatus.value = null
        }
        if (exit != 0) throw IllegalStateException(log.lineSequence().lastOrNull { it.isNotBlank() }?.take(300) ?: "git install failed (exit ${exit ?: "?"})")
        log.toString()
    }

    private suspend fun gitRun(project: Project, command: String): Result<String> = runCatching {
        val (exit, out) = git(project, command)
        if (exit != 0) throw IllegalStateException(out.trim().ifBlank { "git exited with code $exit" })
        out
    }

    // ---- Agent loop --------------------------------------------------------------------------

    fun approve(answer: Approval) {
        approval?.complete(answer)
    }

    fun stop() {
        job?.cancel()
        LocalEngine.cancel()
        viewModelScope.launch { bridge.cancel() }
    }

    /** Kills only the command that is running right now; the agent run continues with a "killed" result. */
    fun killCommand() {
        if (runningIndex == null) return
        killRequested = true
        mcpCall?.cancel()
        viewModelScope.launch { bridge.cancel() }
    }

    /** Builds a session for the active profile: a cloud provider or an on-device model. */
    private suspend fun createSession(restoredHistory: String? = null): ChatModel {
        val active = settings.value
        if (!active.provider.isLocal) return ModelSession(active, context = deviceContext(), restoredHistory = restoredHistory, external = mcp.externalTools)
        val model = localModels.get(active.model)
            ?: throw IllegalStateException("Choose an on-device model in Settings → Local models.")
        val params = withContext(Dispatchers.IO) { store.loadLocalParams() }
        mcp.resetLocalTools()
        return LocalModelSession(getApplication(), model, params, deviceContext(), restoredHistory, mcp.localExternalTools,
            onProgress = { activity.value = it })
    }

    /** Selects a downloaded/added GGUF model as the active (on-device) provider. */
    fun useLocalModel(id: String) {
        if (busy.value || installing.value) return
        val model = localModels.get(id) ?: return
        val value = ProviderSettings(Provider.LOCAL, id, "")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(value) }
                applyProfile(value)
                notice.value = "Using ${model.name} on-device. It loads when you send the first message."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = e.message ?: "Could not select the model."
            }
        }
    }

    /** Shell execution seam; instrumented tests substitute a recorder to observe the context a command runs in. */
    private var shellExecutor: suspend (command: String, linux: Boolean, distro: LinuxDistro, workdir: String) -> Bundle =
        { command, linux, distro, workdir -> bridge.execute(command, linux, distro, workdir) }

    @Volatile private var partialIndex: Int? = null

    /** Shows streamed text from an on-device model in a placeholder message. */
    private fun showPartial(text: String) {
        if (text.isBlank()) return
        _items.update { list ->
            val index = partialIndex
            if (index != null && index < list.size) list.toMutableList().apply { set(index, ChatItem.AgentMsg(text)) }
            else {
                partialIndex = list.size
                list + ChatItem.AgentMsg(text)
            }
        }
    }

    /** Replaces the streamed placeholder with the final text (or removes it if the reply was only a tool call). */
    private fun finishReply(text: String) {
        val index = partialIndex
        partialIndex = null
        _items.update { list ->
            when {
                index != null && index < list.size && text.isNotBlank() -> list.toMutableList().apply { set(index, ChatItem.AgentMsg(text)) }
                index != null && index < list.size -> list.toMutableList().apply { removeAt(index) }
                text.isNotBlank() -> list + ChatItem.AgentMsg(text)
                else -> list
            }
        }
    }

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || busy.value || installing.value) return
        val active = settings.value
        if (active.provider.isLocal) {
            val local = localModels.get(active.model)
            if (local == null || !local.ready) {
                notice.value = "Choose a downloaded on-device model in Settings → Local models first."
                return
            }
        } else if (active.apiKey.isBlank()) {
            notice.value = "Add an API key for ${active.provider.label} in Settings first."
            return
        }
        val earlier = _items.value
        // Pin this request's shell context: project edits while it runs must not redirect its commands.
        val context = RequestContext(activeDistroValue(), activeProject.value?.workdir.orEmpty(), activeProject.value)
        runningContext = context
        _items.update { it + ChatItem.UserMsg(message) }
        persist()
        launchWork {
            val model = session ?: createSession().also { session = it }
            if (!active.provider.isLocal) {
                activity.value = "Connecting tools…"
                mcp.prepare()
            }
            activity.value = "Thinking…"
            val carried = if (model.isEmpty) transcript(earlier) else ""
            model.user(if (carried.isEmpty()) message else "$carried\n\nNew message from the user:\n$message")
            repeat(MAX_ROUNDS) {
                val reply = model.next { partial -> showPartial(partial) }
                finishReply(reply.text.trim())
                if (reply.tools.isEmpty()) return@launchWork
                val results = reply.tools.map { tool -> tool to runTool(tool, context) }
                model.results(results)
                persist()
            }
            session = null
            _items.update { it + ChatItem.AgentMsg("Paused after $MAX_ROUNDS tool rounds. Send a message to continue.") }
        }
    }

    private suspend fun runTool(tool: RequestedTool, context: RequestContext): String {
        if (tool.name.startsWith("mcp__")) return runMcpTool(tool)
        when (prefs.value.policy(tool.name)) {
            ToolPolicy.DENY -> {
                _items.update { it + ChatItem.ToolCall(label(tool, tool.command), "Blocked by Settings (${ToolNames.label(tool.name)}: Deny).", 126) }
                throw ToolRequestDenied(blocked = true)
            }
            ToolPolicy.ALLOW -> return execute(tool, tool.command, context)
            ToolPolicy.ASK -> {
                val decision = CompletableDeferred<Approval>().also { approval = it }
                pending.value = tool
                val answer = try { decision.await() } finally { pending.value = null; approval = null }
                if (!answer.run) {
                    _items.update { it + ChatItem.ToolCall(label(tool, tool.command), "Denied by user; not executed.", 126) }
                    throw ToolRequestDenied()
                }
                if (answer.always) setPolicy(tool.name, ToolPolicy.ALLOW)
                val command = answer.command.trim()
                if (tool.name != ToolNames.SCREEN && (command.isEmpty() || command.length > 8000)) {
                    return "The user cleared the command; nothing was executed."
                }
                val output = execute(tool, command, context)
                return if (command != tool.command.trim()) "Note: the user edited the command before running it. Executed: $command\n$output" else output
            }
        }
    }

    private suspend fun runMcpTool(tool: RequestedTool): String {
        val info = mcp.describe(tool.name)
        if (info == null) {
            _items.update { it + ChatItem.ToolCall(label(tool, tool.command), "Not available: the MCP server is stopped, removed, or the tool is turned off.", 126) }
            return "This MCP tool is not available right now. Carry on without it."
        }
        var arguments = tool.command
        when (info.policy) {
            ToolPolicy.DENY -> {
                _items.update { it + ChatItem.ToolCall(label(tool, tool.command), "Blocked by the ${info.serverName} server settings (Deny).", 126) }
                throw ToolRequestDenied(blocked = true)
            }
            ToolPolicy.ALLOW -> {}
            ToolPolicy.ASK -> {
                val decision = CompletableDeferred<Approval>().also { approval = it }
                pending.value = tool
                val answer = try { decision.await() } finally { pending.value = null; approval = null }
                if (!answer.run) {
                    _items.update { it + ChatItem.ToolCall(label(tool, tool.command), "Denied by user; not executed.", 126) }
                    throw ToolRequestDenied()
                }
                if (answer.always) mcp.setPolicy(info.serverId, ToolPolicy.ALLOW)
                arguments = answer.command.trim()
            }
        }
        val parsed = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return "The edited arguments were not valid JSON; nothing was executed."
        return executeMcp(tool, parsed, edited = arguments != tool.command.trim())
    }

    private suspend fun executeMcp(tool: RequestedTool, arguments: JSONObject, edited: Boolean): String {
        val index = _items.value.size
        runningIndex = index
        killRequested = false
        val card = ChatItem.ToolCall(label(tool, arguments.toString()), "", 0, true)
        _items.update { it + card }
        fun finish(output: String, exit: Int) {
            _items.update { list -> list.toMutableList().apply { if (index < size) set(index, card.copy(output = output, exitCode = exit, running = false)) } }
            runningIndex = null
        }
        val call = viewModelScope.async(Dispatchers.IO) { mcp.call(tool.name, arguments) }
        mcpCall = call
        try {
            val result = call.await()
            finish(result.text, if (result.isError) 1 else 0)
            val body = if (result.isError) "The tool reported an error:\n${result.text}" else result.text
            return if (edited) "Note: the user edited the arguments before running it. Executed with: $arguments\n$body" else body
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().job.isActive) { call.cancel(); throw e }
            val killed = "[Tool call killed by the user before it finished]"
            finish(killed, EXIT_KILLED)
            return "$killed\nThe user killed this call. Do not run it again unless asked; continue with what you have or ask how to proceed."
        } catch (e: Exception) {
            val error = "Tool failed: ${e.message ?: e.javaClass.simpleName}"
            finish(error, 1)
            return error
        } finally {
            mcpCall = null
        }
    }

    private fun label(tool: RequestedTool, command: String) =
        if (tool.name == ToolNames.SCREEN) "read_screen" + (if (command.isBlank()) "" else ": $command") else "${tool.name}: $command"

    private suspend fun execute(tool: RequestedTool, command: String, context: RequestContext): String {
        val index = _items.value.size
        runningIndex = index
        killRequested = false
        val card = ChatItem.ToolCall(label(tool, command), "", 0, true)
        _items.update { it + card }
        fun finish(output: String, exit: Int) {
            _items.update { list -> list.toMutableList().apply { if (index < size) set(index, card.copy(output = output, exitCode = exit, running = false)) } }
            runningIndex = null
        }
        try {
            if (tool.name == ToolNames.SCREEN) {
                val screen = bridge.readScreen(command)
                finish(screen, 0)
                return screen
            }
            val linux = tool.name == ToolNames.LINUX
            val result = shellExecutor(command, linux, context.distro, if (linux) context.workdir else "")
            val output = result.getString("output").orEmpty()
            val exit = result.getInt("exitCode")
            if (killRequested && exit != 0) {
                val killed = output.trimEnd() + "\n[Command killed by the user before it finished]"
                finish(killed, EXIT_KILLED)
                return "exit code $EXIT_KILLED\n$killed\nThe user killed this command. Do not re-run it unless asked; continue with what you have or ask how to proceed."
            }
            finish(output, exit)
            return "exit code $exit\n$output"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = "Tool failed: ${e.message ?: e.javaClass.simpleName}"
            finish(error, 1)
            return error
        }
    }

    /** Live device facts for the system prompt, so the model knows what is actually available. */
    private fun deviceContext(): String = buildString {
        append("Current device context:\n")
        append("- Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}).\n")
        append("- Shizuku: ${bridge.status.value}.\n")
        val distro = runningContext?.distro ?: activeDistroValue()
        append("- Linux environment (linux_shell): ${distro.label}, ${linuxStatus.value.lineSequence().firstOrNull().orEmpty()}. ${distro.agentHint}\n")
        val project = agentProject()
        if (project != null) {
            append("- Project: \"${project.name}\". linux_shell and the terminal run in the working directory ${project.workdir} (relative paths are resolved there).")
            if (project.repoUrl.isNotBlank()) append(" It is a git checkout of ${project.repoUrl}.")
            append("\n")
        }
        append("- Local time: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())}.")
        val custom = prefs.value.instructions.trim()
        if (custom.isNotEmpty()) append("\n\nThe user's custom instructions (follow them unless unsafe):\n").append(custom)
        val projectCustom = project?.instructions?.trim().orEmpty()
        if (projectCustom.isNotEmpty()) append("\n\nInstructions for this project (follow them unless unsafe):\n").append(projectCustom)
        // Skills attached to the active project (progressive disclosure: only attached bodies load).
        val activeSkills = project?.skills.orEmpty()
            .mapNotNull { sid -> skills.value.firstOrNull { it.id == sid && it.enabled } }
            .take(5)
        if (activeSkills.isNotEmpty()) {
            append("\n\nSkills active for this project (follow their guidance):")
            activeSkills.forEach { s -> append("\n\n## Skill: ${s.name}\n").append(s.instructions.trim()) }
        }
    }

    /** Visible conversation, used when a fresh model context starts mid-conversation. */
    private fun transcript(items: List<ChatItem>): String {
        if (items.none { it is ChatItem.UserMsg }) return ""
        val text = items.joinToString("\n") { item ->
            when (item) {
                is ChatItem.UserMsg -> "User: ${item.text}"
                is ChatItem.AgentMsg -> "Assistant: ${item.text}"
                is ChatItem.ToolCall -> "[Tool] ${item.command} -> exit ${item.exitCode}: ${item.output.take(400)}"
            }
        }
        return "Earlier in this conversation (model context was reset; summary of what the user saw):\n" + text.takeLast(12_000)
    }

    private fun launchWork(block: suspend () -> Unit) {
        busy.value = true
        activity.value = "Thinking…"
        notice.value = ""
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: ToolRequestDenied) {
                // Do not let a model work around a refusal with another tool or
                // run the remaining calls in its batch. Reset native/provider
                // history as with Stop, so the next request starts cleanly.
                session = null
                _items.update { it + ChatItem.AgentMsg(if (e.blocked)
                    "This tool is blocked in Settings. The request has stopped."
                else "Tool call denied. The request has stopped.") }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { bridge.cancel() }
                session = null
                _items.update { it + ChatItem.AgentMsg("Stopped. Completed command side effects are not undone.") }
            } catch (e: Exception) {
                session = null
                val problems = settings.value.problems()
                val hint = if (problems.isEmpty()) "" else "\n\nSettings check: " + problems.joinToString(" ") + " Fix it in Settings."
                _items.update { it + ChatItem.AgentMsg("⚠️ ${e.message ?: "Request failed."}$hint") }
            } finally {
                runningIndex?.let { index ->
                    _items.update { list ->
                        list.mapIndexed { i, item ->
                            if (i == index && item is ChatItem.ToolCall) item.copy(output = "Cancelled", exitCode = 130, running = false) else item
                        }
                    }
                }
                runningIndex = null
                partialIndex = null
                pending.value = null
                approval = null
                runningContext = null
                busy.value = false
                persist()
            }
        }
    }

    override fun onCleared() {
        mcp.shutdown()
        snapshot()?.let { snap -> thread(isDaemon = false) { runCatching { conversations.save(snap) } } }
        super.onCleared()
    }

    private companion object {
        const val MAX_ROUNDS = 12
        const val EXIT_KILLED = 137
    }
}

