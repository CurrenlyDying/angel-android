package com.bruh.angel.mcp

/** Maps server tools to the unique, provider-safe names the model sees: `mcp__<server>__<tool>`. */
object McpNaming {
    private const val MAX_TOOL_PART = 40

    data class Ref(val qualified: String, val serverId: String, val tool: McpTool)

    fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifEmpty { "tool" }.take(MAX_TOOL_PART)

    fun qualify(serverId: String, tools: List<McpTool>): List<Ref> {
        val used = HashSet<String>()
        return tools.map { tool ->
            val base = sanitize(tool.name)
            var candidate = base
            var n = 2
            while (!used.add(candidate)) candidate = base.take(MAX_TOOL_PART - 3) + "_" + n++
            Ref("mcp__${serverId}__$candidate", serverId, tool)
        }
    }

    /** "mcp__fetch__fetch" -> ("fetch", "fetch"), or null if it is not an MCP tool name. */
    fun split(qualified: String): Pair<String, String>? {
        if (!qualified.startsWith("mcp__")) return null
        val rest = qualified.removePrefix("mcp__")
        val i = rest.indexOf("__")
        if (i <= 0 || i + 2 >= rest.length) return null
        return rest.substring(0, i) to rest.substring(i + 2)
    }
}
