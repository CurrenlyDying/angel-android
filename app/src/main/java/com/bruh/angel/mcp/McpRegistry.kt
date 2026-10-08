package com.bruh.angel.mcp

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/** Search over the official community registry (registry.modelcontextprotocol.io). */
object McpRegistry {
    private const val BASE = "https://registry.modelcontextprotocol.io/v0/servers"
    private val FLAG = Regex("-{1,2}[A-Za-z0-9][A-Za-z0-9_-]{0,40}")
    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    private val HEADER_NAME = Regex("[A-Za-z0-9-]{1,64}")

    data class Page(val entries: List<McpCatalogEntry>, val nextCursor: String?)

    private val client by lazy { McpNet.client.newBuilder().callTimeout(25, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }

    suspend fun search(query: String, cursor: String? = null): Page = withContext(Dispatchers.IO) {
        val url = buildString {
            append(BASE).append("?limit=30&version=latest")
            if (query.isNotBlank()) append("&search=").append(URLEncoder.encode(query.trim().take(100), "UTF-8"))
            if (!cursor.isNullOrEmpty()) append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
        }
        try {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                if (!r.isSuccessful) throw McpException("The registry answered HTTP ${r.code}")
                val body = r.body?.source()?.let { it.request(4_000_000); it.buffer.snapshot().utf8() }.orEmpty()
                parse(body)
            }
        } catch (e: IOException) {
            throw McpException("Could not reach the MCP registry: ${e.message ?: e.javaClass.simpleName}", cause = e)
        } catch (e: JSONException) {
            throw McpException("The registry returned an unexpected answer")
        }
    }

    fun parse(json: String): Page {
        val root = JSONObject(json)
        val servers = root.optJSONArray("servers")
        val entries = ArrayList<McpCatalogEntry>()
        val seen = HashSet<String>()
        if (servers != null) for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i)?.optJSONObject("server") ?: continue
            val entry = entry(server) ?: continue
            if (seen.add(entry.id)) entries += entry
        }
        val next = root.optJSONObject("metadata")?.optString("nextCursor")?.ifBlank { null }
        return Page(entries, next)
    }

    private fun entry(server: JSONObject): McpCatalogEntry? {
        val fullName = server.optString("name").ifBlank { return null }
        val short = fullName.substringAfterLast('/')
        val publisher = fullName.substringBefore('/').split('.').reversed().joinToString(".")
        val pkg = server.optJSONArray("packages")?.let { list ->
            (0 until list.length()).asSequence().mapNotNull { list.optJSONObject(it) }.firstNotNullOfOrNull(::pkg)
        }
        val remote = server.optJSONArray("remotes")?.let { list ->
            (0 until list.length()).asSequence().mapNotNull { list.optJSONObject(it) }.firstNotNullOfOrNull(::remote)
        }
        if (pkg == null && remote == null) return null
        return McpCatalogEntry(
            id = fullName.lowercase(), name = short, curated = false, publisher = publisher,
            description = server.optString("description"),
            homepage = server.optJSONObject("repository")?.optString("url").orEmpty().ifEmpty { server.optString("websiteUrl") },
            category = "Community", pkg = pkg, remote = remote, approxMb = if (pkg != null) 80 else 0
        )
    }

    private fun pkg(p: JSONObject): McpPackage? {
        val type = when (p.optString("registryType")) { "npm" -> McpPackageType.NPM; "pypi" -> McpPackageType.PYPI; else -> return null }
        val transport = p.optJSONObject("transport")?.optString("type").orEmpty()
        if (transport.isNotEmpty() && transport != "stdio") return null
        val identifier = p.optString("identifier")
        val version = p.optString("version").ifBlank { return null }
        val inputs = ArrayList<McpInput>()
        p.optJSONArray("environmentVariables")?.let { vars ->
            for (i in 0 until vars.length()) {
                val v = vars.optJSONObject(i) ?: continue
                val name = v.optString("name")
                if (!ENV_NAME.matches(name)) continue
                inputs += McpInput(name, name, McpInput.Kind.ENV, v.optString("description"), secret = v.optBoolean("isSecret"),
                    required = v.optBoolean("isRequired"), default = v.optString("default"))
            }
        }
        val fixed = ArrayList<String>()
        p.optJSONArray("packageArguments")?.let { args ->
            for (i in 0 until args.length()) {
                val a = args.optJSONObject(i) ?: continue
                val named = a.optString("type") == "named"
                val flag = a.optString("name")
                if (named && !FLAG.matches(flag)) return null
                val value = a.optString("value")
                val default = a.optString("default")
                val fixedValue = value.takeIf { it.isNotEmpty() && !it.contains('{') }
                if (fixedValue != null) {
                    if (named) fixed += flag
                    fixed += fixedValue
                } else if (a.optBoolean("isRequired") || default.isNotEmpty()) {
                    val label = a.optString("valueHint").ifEmpty { if (named) flag.trimStart('-') else "Argument ${i + 1}" }
                    inputs += McpInput("arg$i", label, McpInput.Kind.ARG, a.optString("description"),
                        flag = if (named) flag else "", secret = a.optBoolean("isSecret"),
                        required = a.optBoolean("isRequired"), default = default)
                }
            }
        }
        val result = McpPackage(type, identifier, version, fixed, inputs)
        return if (McpRecipes.validate(result) == null) result else null
    }

    private fun remote(r: JSONObject): McpRemoteSpec? {
        val type = r.optString("type")
        if (type != "streamable-http" && type != "sse") return null
        val url = r.optString("url")
        if (url.contains('{') || McpNet.check(url) != null) return null
        val inputs = ArrayList<McpInput>()
        r.optJSONArray("headers")?.let { headers ->
            for (i in 0 until headers.length()) {
                val h = headers.optJSONObject(i) ?: continue
                val name = h.optString("name")
                if (!HEADER_NAME.matches(name)) continue
                val template = h.optString("value")
                inputs += McpInput(name, name, McpInput.Kind.HEADER, h.optString("description"),
                    secret = h.optBoolean("isSecret", true), required = h.optBoolean("isRequired"),
                    default = template.replace(Regex("\\{[^}]*\\}"), ""))
            }
        }
        return McpRemoteSpec(url, inputs)
    }
}
