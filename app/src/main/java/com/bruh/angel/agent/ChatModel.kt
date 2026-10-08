package com.bruh.angel.agent

import com.bruh.angel.model.ToolNames
import org.json.JSONObject

/** A conversation with a model that can request tools. Cloud providers and on-device models implement it. */
interface ChatModel {
    /** True until the first message is added (a fresh context). */
    val isEmpty: Boolean

    fun user(text: String)

    fun results(results: List<Pair<RequestedTool, String>>)

    /** Produces the next reply. [onPartial] receives the visible text so far (on-device models stream). */
    suspend fun next(onPartial: (String) -> Unit = {}): ModelReply

    /** Provider-native history, saved with conversations. */
    fun exportHistory(): String
}

/** A tool contributed by an MCP server. [name] is already qualified (`mcp__<server>__<tool>`). */
class ExternalTool(val name: String, val description: String, val schema: JSONObject)

/** Supplies the MCP tools that are available right now; read again before every model request. */
fun interface ExternalTools {
    fun list(): List<ExternalTool>

    companion object { val NONE = ExternalTools { emptyList() } }
}

internal const val MCP_PREFIX = "mcp__"
private val MCP_NAME = Regex("mcp__[a-z0-9-]{1,20}__[A-Za-z0-9_-]{1,64}")

/** Tool schemas shared by every adapter (names in [ToolNames]) plus any MCP tools. */
internal fun toolDeclarations(extra: List<ExternalTool> = emptyList(), gemini: Boolean = false): List<JSONObject> {
    fun commandSchema(example: String) = obj(
        "type" to "object",
        "properties" to obj("command" to obj("type" to "string", "description" to "One shell command, e.g. $example")),
        "required" to arr("command")
    )
    val builtin = listOf(
        obj(
            "name" to ToolNames.ANDROID,
            "description" to "Run one non-interactive command directly on this Android phone in its ADB shell " +
                "(uid 2000 via Shizuku, NOT root). Do not prefix with adb. 60 s timeout; returns exit code and stdout+stderr.",
            "parameters" to commandSchema("dumpsys battery")
        ),
        obj(
            "name" to ToolNames.LINUX,
            "description" to "Run one non-interactive command in the Linux ARM64 userland (proot, simulated root). " +
                "The distribution and package manager are given in the device context; files under /root persist; phone storage is at /sdcard. 60 s timeout.",
            "parameters" to commandSchema("python3 -c 'print(2**10)'")
        ),
        obj(
            "name" to ToolNames.SCREEN,
            "description" to "Read the UI elements currently on the phone screen: text, content descriptions, ids, " +
                "clickable/scrollable state and tap coordinates. Use before and after interacting via `input tap X Y`.",
            "parameters" to obj(
                "type" to "object",
                "properties" to obj(
                    "filter" to obj("type" to "string", "description" to "Optional case-insensitive text filter; empty for all elements")
                )
            )
        )
    )
    return builtin + extra.map { tool ->
        val parameters = ToolSchemas.parameters(tool.schema, gemini)
        obj("name" to tool.name, "description" to ToolSchemas.describe(tool.description, tool.name)).also {
            if (parameters != null) it.put("parameters", parameters)
        }
    }
}

/** org.json escapes every '/' as '\\/'; undo that so arguments read naturally (an escaped backslash before a slash stays). */
private val ESCAPED_SLASH = Regex("""(?<!\\)((?:\\\\)*)\\/""")

/** Validates a model-requested tool call (shared by all adapters). */
internal fun validatedToolCall(id: String, name: String, args: JSONObject): RequestedTool {
    if (MCP_NAME.matches(name)) {
        val raw = args.toString().replace(ESCAPED_SLASH, "$1/")
        if (raw.length > 100_000) throw ProviderException("The model sent oversized tool arguments.")
        return RequestedTool(id, name, raw)
    }
    if (name !in ToolNames.all) throw ProviderException("The model requested an unknown tool '$name'.")
    return if (name == ToolNames.SCREEN) {
        val filter = args.optString("filter", "")
        if (filter.length > 200) throw ProviderException("The model sent an invalid screen filter.")
        RequestedTool(id, name, filter)
    } else {
        val command = args.optString("command", "")
        if (command.isBlank() || command.length > 8000) throw ProviderException("The model sent an invalid command.")
        RequestedTool(id, name, command)
    }
}

