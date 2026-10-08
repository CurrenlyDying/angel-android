package com.bruh.angel.ui.chat

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bruh.angel.agent.ChatModel
import com.bruh.angel.agent.LocalModelSession
import com.bruh.angel.agent.ModelSession
import com.bruh.angel.agent.ProviderApi
import com.bruh.angel.agent.RequestedTool
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.linuxenv.LinuxDownloader
import com.bruh.angel.mcp.McpManager
import com.bruh.angel.local.HuggingFace
import com.bruh.angel.local.LocalEngine
import com.bruh.angel.local.LocalModelStore
import com.bruh.angel.model.AgentPrefs
import com.bruh.angel.model.ChatItem
import com.bruh.angel.model.Conversation
import com.bruh.angel.model.ConversationMeta
import com.bruh.angel.model.ConversationStore
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

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ProviderSettingsStore(application)
    private val conversations = ConversationStore(application)
    val bridge = ShizukuBridge.shared(application)
    private val downloader = LinuxDownloader(application, bridge)
    val mcp = McpManager(bridge, store, viewModelScope) { prefs.value.distro }

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items = _items.asStateFlow()
    val settings = MutableStateFlow(ProviderSettings(Provider.OPENAI, Provider.OPENAI.defaultModel, ""))
    val prefs = MutableStateFlow(AgentPrefs())
    val notice = MutableStateFlow("")
    val busy = MutableStateFlow(true) // until saved settings are loaded
    val installing = MutableStateFlow(false)
    /** Status of the selected distribution: "Ready: ...", "Not installed", or a progress / error line. */
    val linuxStatus = MutableStateFlow("Connect Shizuku to check Linux")
    val installedDistros = MutableStateFlow<Set<LinuxDistro>>(emptySet())
    val installingDistro = MutableStateFlow<LinuxDistro?>(null)
    val installProgress = MutableStateFlow<Float?>(null)
    val pending = MutableStateFlow<RequestedTool?>(null)
    val history = MutableStateFlow<List<ConversationMeta>>(emptyList())

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
            val distro = prefs.value.distro
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
            execute(tool, tool.command)
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
            history = session?.exportHistory()
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
            session = null
            _items.value = emptyList()
        }
    }

    suspend fun transcript(id: String): String? = withContext(Dispatchers.IO) {
        conversations.load(id)?.let { ConversationStore.transcript(it) }
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
        return LocalModelSession(getApplication(), model, params, deviceContext(), restoredHistory, mcp.externalTools)
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
        _items.update { it + ChatItem.UserMsg(message) }
        persist()
        launchWork {
            val model = session ?: createSession().also { session = it }
            mcp.prepare()
            val carried = if (model.isEmpty) transcript(earlier) else ""
            model.user(if (carried.isEmpty()) message else "$carried\n\nNew message from the user:\n$message")
            repeat(MAX_ROUNDS) {
                val reply = model.next { partial -> showPartial(partial) }
                finishReply(reply.text.trim())
                if (reply.tools.isEmpty()) return@launchWork
                val results = reply.tools.map { tool -> tool to runTool(tool) }
                model.results(results)
                persist()
            }
            session = null
            _items.update { it + ChatItem.AgentMsg("Paused after $MAX_ROUNDS tool rounds. Send a message to continue.") }
        }
    }

    private suspend fun runTool(tool: RequestedTool): String {
        if (tool.name.startsWith("mcp__")) return runMcpTool(tool)
        when (prefs.value.policy(tool.name)) {
            ToolPolicy.DENY -> {
                _items.update { it + ChatItem.ToolCall(label(tool, tool.command), "Blocked by Settings (${ToolNames.label(tool.name)}: Deny).", 126) }
                return "Blocked: the user's settings deny the ${tool.name} tool. Do not retry; explain what you would have done instead."
            }
            ToolPolicy.ALLOW -> return execute(tool, tool.command)
            ToolPolicy.ASK -> {
                val decision = CompletableDeferred<Approval>().also { approval = it }
                pending.value = tool
                val answer = try { decision.await() } finally { pending.value = null; approval = null }
                if (answer.always) setPolicy(tool.name, ToolPolicy.ALLOW)
                if (!answer.run) {
                    _items.update { it + ChatItem.ToolCall(label(tool, tool.command), "Denied by user; not executed.", 126) }
                    return "Denied by the user; not executed. Do not retry this without a new request."
                }
                val command = answer.command.trim()
                if (tool.name != ToolNames.SCREEN && (command.isEmpty() || command.length > 8000)) {
                    return "The user cleared the command; nothing was executed."
                }
                val output = execute(tool, command)
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
                return "Blocked: the user does not allow tools from ${info.serverName}. Do not retry."
            }
            ToolPolicy.ALLOW -> {}
            ToolPolicy.ASK -> {
                val decision = CompletableDeferred<Approval>().also { approval = it }
                pending.value = tool
                val answer = try { decision.await() } finally { pending.value = null; approval = null }
                if (answer.always) mcp.setPolicy(info.serverId, ToolPolicy.ALLOW)
                if (!answer.run) {
                    _items.update { it + ChatItem.ToolCall(label(tool, tool.command), "Denied by user; not executed.", 126) }
                    return "Denied by the user; not executed. Do not retry this without a new request."
                }
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

    private suspend fun execute(tool: RequestedTool, command: String): String {
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
            val result = bridge.execute(command, tool.name == ToolNames.LINUX, prefs.value.distro)
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
        val distro = prefs.value.distro
        append("- Linux environment (linux_shell): ${distro.label}, ${linuxStatus.value.lineSequence().firstOrNull().orEmpty()}. ${distro.agentHint}\n")
        append("- Local time: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())}.")
        val custom = prefs.value.instructions.trim()
        if (custom.isNotEmpty()) append("\n\nThe user's custom instructions (follow them unless unsafe):\n").append(custom)
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
        notice.value = ""
        job = viewModelScope.launch {
            try {
                block()
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

