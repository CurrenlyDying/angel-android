package com.bruh.angel.mcp

import com.bruh.angel.mcp.McpInput.Kind

/**
 * Hand-picked servers with pinned versions. Everything installs into /root/.angel/mcp/<id> inside the Linux
 * userland (npm packages into node_modules, Python packages into a virtualenv), so removing a server is one rm -rf.
 */
object McpCatalog {
    private const val NPM_SERVERS = "https://github.com/modelcontextprotocol/servers"

    val curated: List<McpCatalogEntry> = listOf(
        McpCatalogEntry(
            id = "fetch", name = "Fetch", category = "Web", curated = true, approxMb = 60,
            description = "Downloads a web page and converts it to clean Markdown so the agent can read articles and documentation.",
            publisher = "Model Context Protocol", homepage = NPM_SERVERS,
            pkg = McpPackage(McpPackageType.PYPI, "mcp-server-fetch", "2026.8.18")
        ),
        McpCatalogEntry(
            id = "duckduckgo", name = "DuckDuckGo search", category = "Web", curated = true, approxMb = 50,
            description = "Web search and page fetching through DuckDuckGo. No account or API key needed.",
            publisher = "nickclyde", homepage = "https://github.com/nickclyde/duckduckgo-mcp-server",
            pkg = McpPackage(McpPackageType.PYPI, "duckduckgo-mcp-server", "0.7.0")
        ),
        McpCatalogEntry(
            id = "filesystem", name = "Filesystem", category = "Files", curated = true, approxMb = 40,
            description = "Read, write, search and edit files in the folders you allow. Use /sdcard to reach phone storage.",
            publisher = "Model Context Protocol", homepage = NPM_SERVERS, needsNetwork = false,
            pkg = McpPackage(
                McpPackageType.NPM, "@modelcontextprotocol/server-filesystem", "2026.8.31",
                inputs = listOf(
                    McpInput("dirs", "Allowed folders", Kind.ARG, required = true, default = "/root",
                        description = "Space-separated paths inside Linux. The server can only touch these folders.")
                )
            )
        ),
        McpCatalogEntry(
            id = "memory", name = "Memory", category = "Knowledge", curated = true, approxMb = 40,
            description = "A small knowledge graph the agent can write to, so it remembers people, projects and facts between chats.",
            publisher = "Model Context Protocol", homepage = NPM_SERVERS, needsNetwork = false,
            pkg = McpPackage(
                McpPackageType.NPM, "@modelcontextprotocol/server-memory", "2026.8.31",
                inputs = listOf(
                    McpInput("MEMORY_FILE_PATH", "Memory file", Kind.ENV, default = "/root/.angel/memory.json",
                        description = "Where the graph is stored inside Linux.")
                )
            )
        ),
        McpCatalogEntry(
            id = "thinking", name = "Sequential thinking", category = "Reasoning", curated = true, approxMb = 40,
            description = "A scratchpad tool that helps the model break hard problems into revisable steps.",
            publisher = "Model Context Protocol", homepage = NPM_SERVERS, needsNetwork = false,
            pkg = McpPackage(McpPackageType.NPM, "@modelcontextprotocol/server-sequential-thinking", "2026.8.31")
        ),
        McpCatalogEntry(
            id = "time", name = "Time & timezones", category = "Utilities", curated = true, approxMb = 45, needsNetwork = false,
            description = "Current time in any timezone and conversions between zones.",
            publisher = "Model Context Protocol", homepage = NPM_SERVERS,
            pkg = McpPackage(
                McpPackageType.PYPI, "mcp-server-time", "2026.8.18",
                inputs = listOf(
                    McpInput("tz", "Local timezone", Kind.ARG, flag = "--local-timezone",
                        description = "IANA name such as Europe/Paris. Leave empty to use the system default.")
                )
            )
        ),
        McpCatalogEntry(
            id = "context7", name = "Context7 docs", category = "Developer", curated = true, approxMb = 60,
            description = "Up-to-date library documentation and code examples, pulled on demand.",
            publisher = "Upstash", homepage = "https://github.com/upstash/context7",
            pkg = McpPackage(
                McpPackageType.NPM, "@upstash/context7-mcp", "4.2.0",
                inputs = listOf(
                    McpInput("CONTEXT7_API_KEY", "API key (optional)", Kind.ENV, secret = true,
                        description = "Raises rate limits. Get one at context7.com.")
                )
            )
        )
    )

    fun byId(id: String) = curated.firstOrNull { it.id == id }
}
