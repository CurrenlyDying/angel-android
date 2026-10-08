package com.bruh.angel.mcp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** A JSON-RPC 2.0 MCP client over any [McpTransport]. */
class McpClient(
    private val transport: McpTransport,
    private val onLog: (String) -> Unit,
    private val onClosed: (String) -> Unit,
    private val onToolsChanged: () -> Unit
) {
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    @Volatile private var closed = false
    var serverInfo: String = ""; private set
    var instructions: String = ""; private set

    suspend fun connect() {
        transport.start(::dispatch, ::fail, onLog)
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "angel").put("version", "1.0"))
        val result = request("initialize", params, CONNECT_TIMEOUT_MS)
        val negotiated = result.optString("protocolVersion", PROTOCOL_VERSION)
        if (negotiated !in SUPPORTED_VERSIONS) throw McpException("The server speaks an unsupported protocol version ($negotiated)")
        transport.setProtocolVersion(negotiated)
        val info = result.optJSONObject("serverInfo")
        serverInfo = listOfNotNull(info?.optString("title")?.ifBlank { null } ?: info?.optString("name")?.ifBlank { null },
            info?.optString("version")?.ifBlank { null }).joinToString(" ")
        instructions = result.optString("instructions").take(2000)
        if (result.optJSONObject("capabilities")?.has("tools") == false) throw McpException("The server does not offer any tools")
        transport.send(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
    }

    suspend fun listTools(): List<McpTool> {
        val tools = ArrayList<McpTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = JSONObject().apply { if (cursor != null) put("cursor", cursor) }
            val result = request("tools/list", params, REQUEST_TIMEOUT_MS)
            val arr = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val name = t.optString("name")
                if (name.isBlank()) continue
                tools += McpTool(
                    name = name,
                    title = t.optString("title").ifBlank { t.optJSONObject("annotations")?.optString("title").orEmpty() },
                    description = t.optString("description"),
                    schema = t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"),
                    readOnly = t.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) ?: false
                )
            }
            cursor = result.optString("nextCursor").ifBlank { null }
        } while (cursor != null && ++pages < 20 && tools.size < 500)
        return tools
    }

    suspend fun callTool(name: String, arguments: JSONObject, timeoutMs: Long = CALL_TIMEOUT_MS): McpCallResult {
        val params = JSONObject().put("name", name).put("arguments", arguments)
        val result = request("tools/call", params, timeoutMs)
        return McpCallResult(contentToText(result), result.optBoolean("isError", false))
    }

    suspend fun request(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
        if (closed) throw McpException("Server is not running")
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            transport.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
            val reply = withTimeout(timeoutMs) { deferred.await() }
            reply.optJSONObject("error")?.let {
                throw McpException(it.optString("message", "Server error").take(400), it.optInt("code"))
            }
            return reply.optJSONObject("result") ?: JSONObject()
        } catch (e: TimeoutCancellationException) {
            notifyCancelled(id, "timeout")
            throw McpException("The server did not answer within ${timeoutMs / 1000} seconds")
        } catch (e: CancellationException) {
            notifyCancelled(id, "cancelled")
            throw e
        } finally {
            pending.remove(id)
        }
    }

    private fun notifyCancelled(id: Long, reason: String) {
        if (closed) return
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    withTimeout(2000) {
                        transport.send(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/cancelled")
                            .put("params", JSONObject().put("requestId", id).put("reason", reason)))
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun dispatch(msg: JSONObject) {
        val method = msg.optString("method")
        when {
            method.isEmpty() && msg.has("id") -> {
                val id = msg.optLong("id", -1)
                pending[id]?.complete(msg)
            }
            method == "notifications/tools/list_changed" -> onToolsChanged()
            method == "ping" && msg.has("id") -> reply(msg, JSONObject())
            method.isNotEmpty() && msg.has("id") -> replyError(msg, -32601, "Method not supported by this client")
        }
    }

    private fun reply(request: JSONObject, result: JSONObject) = respond(JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result))
    private fun replyError(request: JSONObject, code: Int, message: String) = respond(
        JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("error", JSONObject().put("code", code).put("message", message))
    )

    private fun respond(message: JSONObject) {
        Thread { runCatching { kotlinx.coroutines.runBlocking { transport.send(message) } } }.apply { isDaemon = true }.start()
    }

    private fun fail(reason: String) {
        if (closed) return
        closed = true
        val error = McpException(reason)
        pending.values.forEach { it.completeExceptionally(error) }
        onClosed(reason)
    }

    fun close() {
        if (closed) return
        closed = true
        val error = McpException("Server stopped")
        pending.values.forEach { it.completeExceptionally(error) }
        transport.close()
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        val SUPPORTED_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
        const val CONNECT_TIMEOUT_MS = 60_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val CALL_TIMEOUT_MS = 120_000L
        const val MAX_RESULT_CHARS = 30_000

        /** Flattens a tools/call result into text: text blocks verbatim, other blocks as short placeholders. */
        fun contentToText(result: JSONObject): String {
            val sb = StringBuilder()
            val content = result.optJSONArray("content")
            if (content != null) for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (sb.isNotEmpty()) sb.append('\n')
                when (block.optString("type")) {
                    "text" -> sb.append(block.optString("text"))
                    "image" -> sb.append("[image ${block.optString("mimeType")} omitted]")
                    "audio" -> sb.append("[audio ${block.optString("mimeType")} omitted]")
                    "resource" -> {
                        val res = block.optJSONObject("resource")
                        val text = res?.optString("text").orEmpty()
                        if (text.isNotEmpty()) sb.append(text) else sb.append("[resource ${res?.optString("uri").orEmpty()}]")
                    }
                    "resource_link" -> sb.append("[link ${block.optString("name")} ${block.optString("uri")}]")
                    else -> sb.append("[${block.optString("type")} content omitted]")
                }
            }
            if (sb.isEmpty()) result.optJSONObject("structuredContent")?.let { sb.append(it.toString()) }
            if (sb.isEmpty()) sb.append("(no output)")
            return if (sb.length > MAX_RESULT_CHARS)
                sb.substring(0, MAX_RESULT_CHARS) + "\n[output truncated: ${sb.length - MAX_RESULT_CHARS} more characters]"
            else sb.toString()
        }
    }
}
