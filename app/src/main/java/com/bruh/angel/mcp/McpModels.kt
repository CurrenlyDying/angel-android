package com.bruh.angel.mcp

import com.bruh.angel.model.ToolPolicy
import org.json.JSONArray
import org.json.JSONObject

enum class McpKind { CATALOG, CUSTOM, REMOTE }

enum class McpRuntime(val id: String, val label: String) {
    LINUX("linux", "Linux"),
    ANDROID("android", "Android shell");

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: LINUX
    }
}

/** A configured MCP server. Everything here is persisted (encrypted, because env/headers may hold secrets). */
data class McpServerConfig(
    val id: String,
    val name: String,
    val kind: McpKind,
    val runtime: McpRuntime = McpRuntime.LINUX,
    /** Shell command that starts a stdio server (inside the Linux userland or the Android shell). */
    val command: String = "",
    /** Shell script that installs the server; re-run on "Reinstall" and when the Linux distro changes. */
    val installScript: String = "",
    val url: String = "",
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val policy: ToolPolicy = ToolPolicy.ASK,
    /** LinuxDistro id the server was installed into ("" when not installed / not applicable). */
    val installedDistro: String = "",
    val disabledTools: Set<String> = emptySet(),
    val description: String = "",
    val source: String = ""
) {
    val isRemote get() = kind == McpKind.REMOTE

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind.name).put("runtime", runtime.id)
        .put("command", command).put("installScript", installScript).put("url", url)
        .put("env", JSONObject(env)).put("headers", JSONObject(headers))
        .put("enabled", enabled).put("policy", policy.name).put("installedDistro", installedDistro)
        .put("disabledTools", JSONArray(disabledTools.toList()))
        .put("description", description).put("source", source)

    companion object {
        fun fromJson(o: JSONObject): McpServerConfig? {
            val id = o.optString("id")
            if (!ID.matches(id)) return null
            fun map(key: String): Map<String, String> {
                val m = o.optJSONObject(key) ?: return emptyMap()
                return m.keys().asSequence().associateWith { m.optString(it) }
            }
            val disabled = o.optJSONArray("disabledTools")
            return McpServerConfig(
                id = id,
                name = o.optString("name", id).ifBlank { id },
                kind = runCatching { McpKind.valueOf(o.optString("kind")) }.getOrDefault(McpKind.CUSTOM),
                runtime = McpRuntime.fromId(o.optString("runtime")),
                command = o.optString("command"),
                installScript = o.optString("installScript"),
                url = o.optString("url"),
                env = map("env"),
                headers = map("headers"),
                enabled = o.optBoolean("enabled", true),
                policy = runCatching { ToolPolicy.valueOf(o.optString("policy")) }.getOrDefault(ToolPolicy.ASK),
                installedDistro = o.optString("installedDistro"),
                disabledTools = if (disabled == null) emptySet() else (0 until disabled.length()).map { disabled.optString(it) }.toSet(),
                description = o.optString("description"),
                source = o.optString("source")
            )
        }

        val ID = Regex("[a-z0-9][a-z0-9-]{0,19}")

        /** Lowercase slug that is safe in tool names and paths; [taken] ids get a numeric suffix. */
        fun slug(name: String, taken: Set<String>): String {
            val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(16).trim('-').ifEmpty { "server" }
            var candidate = base
            var n = 2
            while (candidate in taken) candidate = "$base-${n++}"
            return candidate
        }
    }
}

data class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val schema: JSONObject,
    val readOnly: Boolean
)

enum class McpState { OFF, STARTING, READY, ERROR, NEEDS_INSTALL, INSTALLING }

/** Live view of one server for the UI. */
data class McpServerState(
    val config: McpServerConfig,
    val state: McpState = McpState.OFF,
    val message: String = "",
    val serverInfo: String = "",
    val tools: List<McpTool> = emptyList(),
    val log: List<String> = emptyList()
)

/** Result of a tools/call, already reduced to text for the model. */
data class McpCallResult(val text: String, val isError: Boolean)

enum class McpPackageType(val label: String) { NPM("npm"), PYPI("PyPI") }

data class McpInput(
    val key: String,
    val label: String,
    val kind: Kind,
    val description: String = "",
    /** For named command-line arguments: the flag that precedes the value (e.g. --repository). */
    val flag: String = "",
    val secret: Boolean = false,
    val required: Boolean = false,
    val default: String = ""
) {
    enum class Kind { ENV, ARG, HEADER }
}

data class McpPackage(
    val type: McpPackageType,
    val identifier: String,
    val version: String,
    val fixedArgs: List<String> = emptyList(),
    val inputs: List<McpInput> = emptyList(),
    /** Extra system tools (apt/apk package names are identical for these) the server shells out to. */
    val system: List<String> = emptyList()
)

data class McpRemoteSpec(val url: String, val inputs: List<McpInput> = emptyList())

/** One installable item in the browser: a curated server or a community-registry result. */
data class McpCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val publisher: String,
    val curated: Boolean,
    val homepage: String = "",
    val category: String = "",
    val pkg: McpPackage? = null,
    val remote: McpRemoteSpec? = null,
    val needsNetwork: Boolean = true,
    val approxMb: Int = 0
)
