package com.bruh.angel.mcp

import com.bruh.angel.agent.ExternalTool
import com.bruh.angel.agent.ExternalTools
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.model.ProviderSettingsStore
import com.bruh.angel.model.ToolPolicy
import com.bruh.angel.shizuku.ShizukuBridge
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Installs, starts and talks to MCP servers, and exposes their tools to the agent. */
class McpManager(
    private val bridge: ShizukuBridge,
    private val store: ProviderSettingsStore,
    private val scope: CoroutineScope,
    private val selectedDistro: () -> LinuxDistro
) {
    private val _servers = MutableStateFlow<List<McpServerState>>(emptyList())
    val servers: StateFlow<List<McpServerState>> = _servers.asStateFlow()

    private val clients = ConcurrentHashMap<String, McpClient>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val everReady = ConcurrentHashMap.newKeySet<String>()
    private val loaded = CompletableDeferred<Unit>()
    private val saveLock = Any()

    fun load() {
        scope.launch(Dispatchers.IO) {
            val configs = runCatching { store.loadMcp() }.getOrDefault(emptyList())
            _servers.value = configs.map { McpServerState(it, restState(it)) }
            loaded.complete(Unit)
        }
    }

    private fun needsInstall(c: McpServerConfig) =
        !c.isRemote && c.runtime == McpRuntime.LINUX && c.installedDistro.isEmpty() &&
            (c.kind == McpKind.CATALOG || c.installScript.isNotBlank())

    private fun restState(c: McpServerConfig) = if (needsInstall(c)) McpState.NEEDS_INSTALL else McpState.OFF

    private fun persist() {
        val configs = _servers.value.map { it.config }
        scope.launch(Dispatchers.IO) { synchronized(saveLock) { runCatching { store.saveMcp(configs) } } }
    }

    private fun get(id: String) = _servers.value.firstOrNull { it.config.id == id }

    private fun patch(id: String, change: (McpServerState) -> McpServerState) =
        _servers.update { list -> list.map { if (it.config.id == id) change(it) else it } }

    private fun log(id: String, line: String) = patch(id) { it.copy(log = (it.log + line).takeLast(MAX_LOG)) }

    private fun editConfig(id: String, change: (McpServerConfig) -> McpServerConfig) {
        patch(id) { it.copy(config = change(it.config)) }
        persist()
    }

    // ---- configuration ----------------------------------------------------------------------

    fun taken() = _servers.value.map { it.config.id }.toSet()

    /** Adds a server (rejects duplicates) and persists it. Returns the stored config. */
    fun add(config: McpServerConfig): McpServerConfig {
        require(McpServerConfig.ID.matches(config.id)) { "Invalid server id" }
        require(config.id !in taken()) { "A server with that id already exists" }
        _servers.update { it + McpServerState(config, restState(config)) }
        persist()
        return config
    }

    fun setEnabled(id: String, enabled: Boolean) {
        editConfig(id) { it.copy(enabled = enabled) }
        if (enabled) start(id) else stop(id)
    }

    fun setPolicy(id: String, policy: ToolPolicy) = editConfig(id) { it.copy(policy = policy) }

    fun setToolEnabled(id: String, tool: String, enabled: Boolean) =
        editConfig(id) { it.copy(disabledTools = if (enabled) it.disabledTools - tool else it.disabledTools + tool) }

    fun updateSecrets(id: String, env: Map<String, String>, headers: Map<String, String>) {
        editConfig(id) { it.copy(env = env, headers = headers) }
        if (get(id)?.state == McpState.READY || get(id)?.state == McpState.ERROR) restart(id)
    }

    fun update(id: String, change: (McpServerConfig) -> McpServerConfig) {
        editConfig(id, change)
        if (get(id)?.config?.enabled == true) restart(id)
    }

    fun remove(id: String) {
        val state = get(id) ?: return
        stop(id)
        _servers.update { list -> list.filterNot { it.config.id == id } }
        persist()
        val c = state.config
        if (c.kind == McpKind.CATALOG && c.installedDistro.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                runCatching { bridge.execute(McpRecipes.removeScript(c.id), true, LinuxDistro.fromId(c.installedDistro)) }
            }
        }
    }

    /** A Linux userland is going away: servers living in it must stop and be reinstalled. */
    fun distroRemoved(distro: LinuxDistro) {
        _servers.value.filter { it.config.installedDistro == distro.id }.forEach { s ->
            stop(s.config.id)
            patch(s.config.id) { it.copy(config = it.config.copy(installedDistro = ""), state = McpState.NEEDS_INSTALL, message = "${distro.label} was removed") }
        }
        persist()
    }

    // ---- lifecycle --------------------------------------------------------------------------

    fun start(id: String): Job = synchronized(jobs) {
        jobs[id]?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch(Dispatchers.IO, CoroutineStart.LAZY) {
            try { doStart(id) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(id, e.message ?: e.javaClass.simpleName) }
        }
        jobs[id] = job
        job.start()
        job
    }

    fun restart(id: String) {
        stop(id)
        if (get(id)?.config?.enabled != false) start(id)
    }

    fun stop(id: String) {
        synchronized(jobs) { jobs.remove(id)?.cancel() }
        clients.remove(id)?.close()
        val s = get(id) ?: return
        if (s.state != McpState.NEEDS_INSTALL && s.state != McpState.ERROR) {
            patch(id) { it.copy(state = restState(it.config), message = "", tools = emptyList()) }
        } else if (s.state == McpState.ERROR) patch(id) { it.copy(tools = emptyList()) }
    }

    fun shutdown() {
        jobs.values.forEach { it.cancel() }
        clients.values.forEach { it.close() }
        clients.clear()
    }

    private fun fail(id: String, message: String) {
        clients.remove(id)?.close()
        patch(id) { it.copy(state = McpState.ERROR, message = message.take(300), tools = emptyList()) }
    }

    /** Starts every enabled server that is not running (and reconnects ones that dropped) and waits briefly for them. */
    suspend fun prepare(timeoutMs: Long = 25_000) {
        loaded.await()
        val now = _servers.value
        val starting = now.filter { s ->
            s.config.enabled && (s.state == McpState.OFF || (s.state == McpState.ERROR && s.config.id in everReady))
        }.map { start(it.config.id) }
        val running = now.filter { it.state == McpState.STARTING }.mapNotNull { jobs[it.config.id] }
        withTimeoutOrNull(timeoutMs) { (starting + running).joinAll() }
    }

    private suspend fun readyDistro(config: McpServerConfig): LinuxDistro {
        val distro = config.installedDistro.takeIf { it.isNotEmpty() }?.let(LinuxDistro::fromId) ?: selectedDistro()
        val installed = withContext(Dispatchers.IO) { bridge.api().linuxInstalled() }.split(',')
        if (distro.id !in installed) throw McpException("${distro.label} is not installed. Set it up in Settings first.")
        return distro
    }

    private suspend fun doStart(id: String) {
        val config = get(id)?.config ?: return
        clients.remove(id)?.close()
        if (needsInstall(config)) { patch(id) { it.copy(state = McpState.NEEDS_INSTALL) }; return }
        patch(id) { it.copy(state = McpState.STARTING, message = "Starting…", log = emptyList(), tools = emptyList(), serverInfo = "") }
        val client = withTimeout(CONNECT_BUDGET_MS) {
            if (config.isRemote) connectRemote(config) else connectLocal(config)
        }
        clients[id] = client
        val tools = try { withTimeout(40_000) { client.listTools() } } catch (e: Exception) { clients.remove(id); client.close(); throw e }
        everReady += id
        patch(id) { it.copy(state = McpState.READY, message = "", serverInfo = client.serverInfo, tools = tools) }
    }

    private suspend fun connectLocal(config: McpServerConfig): McpClient {
        if (config.command.isBlank()) throw McpException("No start command is set for this server")
        val distro = if (config.runtime == McpRuntime.LINUX) readyDistro(config) else selectedDistro()
        val client = newClient(config.id) { StdioTransport(bridge, config, distro) }
        try { client.connect() } catch (e: Exception) {
            client.close()
            val tail = get(config.id)?.log?.takeLast(4)?.joinToString("\n").orEmpty()
            throw if (e is McpException && tail.isNotEmpty()) McpException(e.message + "\n" + tail, e.code, e) else e
        }
        return client
    }

    private suspend fun connectRemote(config: McpServerConfig): McpClient {
        McpNet.check(config.url)?.let { throw McpException(it) }
        val factories = listOf<() -> McpTransport>(
            { StreamableHttpTransport(config.url, config.headers) },
            { LegacySseTransport(config.url, config.headers) }
        )
        var last: Exception? = null
        for (factory in factories) {
            val client = newClient(config.id, factory)
            try { client.connect(); return client }
            catch (e: McpTransportUnsupported) { client.close(); last = e }
            catch (e: Exception) { client.close(); throw e }
        }
        throw McpException("That address does not speak MCP. Check the URL (often ending in /mcp or /sse).", cause = last)
    }

    private fun newClient(id: String, transport: () -> McpTransport): McpClient {
        lateinit var self: McpClient
        self = McpClient(
            transport(),
            onLog = { line -> log(id, line) },
            onClosed = { reason ->
                if (clients[id] === self) {
                    clients.remove(id)
                    patch(id) { it.copy(state = McpState.ERROR, message = reason, tools = emptyList()) }
                }
            },
            onToolsChanged = { scope.launch(Dispatchers.IO) { refreshTools(id, self) } }
        )
        return self
    }

    private suspend fun refreshTools(id: String, client: McpClient) {
        if (clients[id] !== client) return
        runCatching { client.listTools() }.onSuccess { tools -> patch(id) { it.copy(tools = tools) } }
    }

    // ---- install ----------------------------------------------------------------------------

    /** Script that installs [config]: regenerated from its package for the current distro, or the user's own. */
    private fun scriptFor(config: McpServerConfig, distro: LinuxDistro): String? {
        McpRecipes.packageFromSource(config.source)?.let { return McpRecipes.installScript(config.id, it, distro) }
        return config.installScript.takeIf { it.isNotBlank() }
    }

    fun install(id: String): Job = synchronized(jobs) {
        jobs[id]?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch(Dispatchers.IO, CoroutineStart.LAZY) {
            try { doInstall(id) } catch (e: CancellationException) {
                patch(id) { it.copy(state = McpState.ERROR, message = "Installation cancelled") }
                throw e
            } catch (e: Exception) { fail(id, e.message ?: e.javaClass.simpleName) }
        }
        jobs[id] = job
        job.start()
        job
    }

    private suspend fun doInstall(id: String) {
        val config = get(id)?.config ?: return
        clients.remove(id)?.close()
        val linux = config.runtime == McpRuntime.LINUX
        val distro = selectedDistro()
        val script = scriptFor(config, distro) ?: throw McpException("This server has nothing to install")
        patch(id) { it.copy(state = McpState.INSTALLING, message = "Installing…", log = emptyList(), tools = emptyList()) }
        if (linux) {
            val installed = withContext(Dispatchers.IO) { bridge.api().linuxInstalled() }.split(',')
            if (distro.id !in installed) throw McpException("${distro.label} is not installed. Set it up in Settings first.")
        }
        val handle = bridge.spawn(script, linux, distro, mergeStderr = true)
        var exit: Int? = null
        try {
            val reader = scope.launch(Dispatchers.IO) {
                BufferedReader(InputStreamReader(handle.stdout, Charsets.UTF_8)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.isNotBlank()) {
                            log(id, line.take(400))
                            if (line.startsWith("==> ")) patch(id) { it.copy(message = line.removePrefix("==> ").take(120)) }
                        }
                    }
                }
            }
            withTimeout(INSTALL_BUDGET_MS) { reader.join() }
            repeat(50) { if (exit == null) { exit = handle.exitCode(); if (exit == null) delay(100) } }
        } finally {
            handle.kill()
        }
        if (exit != 0) {
            val tail = get(id)?.log?.takeLast(3)?.joinToString("\n").orEmpty()
            throw McpException("Installation failed" + (exit?.let { " (exit code $it)" } ?: "") + if (tail.isNotEmpty()) ":\n$tail" else "")
        }
        editConfig(id) { it.copy(installedDistro = if (linux) distro.id else "") }
        patch(id) { it.copy(state = McpState.OFF, message = "") }
        doStart(id)
    }

    // ---- tools for the agent ----------------------------------------------------------------

    private fun refs(): Map<String, McpNaming.Ref> {
        val out = LinkedHashMap<String, McpNaming.Ref>()
        for (s in _servers.value) {
            if (s.state != McpState.READY || !s.config.enabled || s.config.policy == ToolPolicy.DENY) continue
            for (ref in McpNaming.qualify(s.config.id, s.tools.filter { it.name !in s.config.disabledTools })) {
                if (out.size < MAX_TOOLS) out[ref.qualified] = ref
            }
        }
        return out
    }

    val externalTools = ExternalTools {
        refs().values.map { ref ->
            val server = get(ref.serverId)?.config?.name.orEmpty()
            ExternalTool(ref.qualified, "[$server] ${ref.tool.description.ifBlank { ref.tool.title.ifBlank { ref.tool.name } }}", ref.tool.schema)
        }
    }

    data class ToolInfo(val serverId: String, val serverName: String, val tool: String, val readOnly: Boolean, val policy: ToolPolicy)

    fun describe(qualified: String): ToolInfo? {
        val ref = refs()[qualified] ?: return null
        val config = get(ref.serverId)?.config ?: return null
        return ToolInfo(config.id, config.name, ref.tool.name, ref.tool.readOnly, config.policy)
    }

    /** Looks up display info even when the tool is currently unavailable (history, blocked calls). */
    fun label(qualified: String): Pair<String, String>? {
        val (serverId, tool) = McpNaming.split(qualified) ?: return null
        val server = get(serverId)?.config?.name ?: serverId
        val original = get(serverId)?.tools?.firstOrNull { McpNaming.sanitize(it.name) == tool }?.name ?: tool
        return server to original
    }

    suspend fun call(qualified: String, arguments: JSONObject): McpCallResult {
        val ref = refs()[qualified]
            ?: return McpCallResult("This MCP tool is not available (the server is stopped, was removed, or the tool is turned off).", true)
        var client = clients[ref.serverId]
        if (client == null) {
            start(ref.serverId).join()
            client = clients[ref.serverId]
                ?: return McpCallResult("The MCP server \"${ref.serverId}\" is not running: ${get(ref.serverId)?.message.orEmpty()}", true)
        }
        return try {
            client.callTool(ref.tool.name, arguments)
        } catch (e: McpException) {
            McpCallResult("MCP error: ${e.message}", true)
        }
    }

    companion object {
        const val MAX_LOG = 300
        const val MAX_TOOLS = 64
        const val CONNECT_BUDGET_MS = 120_000L
        const val INSTALL_BUDGET_MS = 25 * 60_000L
    }
}
