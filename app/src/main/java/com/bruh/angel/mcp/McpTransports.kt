package com.bruh.angel.mcp

import com.bruh.angel.shizuku.ShizukuBridge
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** Failure while talking to a server; [message] is shown to the user as-is. */
class McpException(message: String, val code: Int = 0, cause: Throwable? = null) : Exception(message, cause)

/** Raised when a remote does not speak Streamable HTTP so the legacy SSE transport should be tried. */
class McpTransportUnsupported(message: String) : Exception(message)

interface McpTransport {
    /** Starts the transport; [onMessage] receives every JSON-RPC message from the server. */
    suspend fun start(onMessage: (JSONObject) -> Unit, onClosed: (String) -> Unit, onLog: (String) -> Unit)
    suspend fun send(message: JSONObject)
    fun setProtocolVersion(version: String) {}
    fun close()
}

/** Runs the server as a child process (Linux userland or Android shell) and speaks newline-delimited JSON on stdio. */
class StdioTransport(
    private val bridge: ShizukuBridge,
    private val config: McpServerConfig,
    private val distro: com.bruh.angel.linuxenv.LinuxDistro
) : McpTransport {
    private var handle: ShizukuBridge.SpawnHandle? = null
    private val writeLock = Any()
    @Volatile private var closed = false

    override suspend fun start(onMessage: (JSONObject) -> Unit, onClosed: (String) -> Unit, onLog: (String) -> Unit) {
        val env = config.env.map { "${it.key}=${it.value}" }
        val h = bridge.spawn(config.command, config.runtime == McpRuntime.LINUX, distro, env)
        handle = h
        daemon("mcp-out-${config.id}") {
            val reader = BufferedReader(InputStreamReader(h.stdout, Charsets.UTF_8))
            runCatching {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val json = if (line.startsWith("{")) runCatching { JSONObject(line) }.getOrNull() else null
                    if (json != null) onMessage(json) else onLog(line.take(500))
                }
            }
            if (!closed) {
                closed = true
                Thread.sleep(150)
                onClosed("Server exited" + (h.exitCode()?.let { " (code $it)" } ?: ""))
            }
        }
        daemon("mcp-err-${config.id}") {
            val reader = BufferedReader(InputStreamReader(h.stderr, Charsets.UTF_8))
            runCatching { while (true) { val line = reader.readLine() ?: break; if (line.isNotBlank()) onLog(line.take(500)) } }
        }
    }

    override suspend fun send(message: JSONObject) {
        val h = handle ?: throw McpException("Server is not running")
        withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                try {
                    h.stdin.write((message.toString() + "\n").toByteArray(Charsets.UTF_8))
                    h.stdin.flush()
                } catch (e: IOException) {
                    throw McpException("Server closed its input", cause = e)
                }
            }
        }
    }

    override fun close() {
        closed = true
        handle?.kill()
    }

    private fun daemon(name: String, block: () -> Unit) = Thread(block, name).apply { isDaemon = true }.start()
}

/** Validation for URLs a user types in; plain http is only allowed for hosts on a private network. */
object McpNet {
    /** Returns an error message, or null when [url] is acceptable. */
    fun check(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "That is not a valid URL"
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: return "The URL needs a host name or IP address"
        if (uri.userInfo != null) return "Put credentials in a header instead of the URL"
        return when (scheme) {
            "https" -> null
            "http" -> if (isPrivateHost(host)) null
            else "Plain http is only allowed for hosts on your own network. Use https for $host."
            else -> "The URL must start with http:// or https://"
        }
    }

    /** Normalises what users type: "192.168.1.5:8000/mcp" gets a scheme, trailing whitespace goes away. */
    fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty() || t.contains("://")) return t
        val host = t.substringBefore('/').substringBeforeLast(':').trim('[', ']')
        return (if (isPrivateHost(host.lowercase())) "http://" else "https://") + t
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") ||
            h.endsWith(".internal") || h.endsWith(".ts.net")) return true
        if (!h.contains('.') && !h.contains(':')) return true
        val literal = h.matches(Regex("[0-9.]+")) || h.contains(':')
        if (!literal) return false
        val addr = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) return true
        val b = addr.address
        if (b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64) return true
        return b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private val JSON_TYPE = "application/json".toMediaType()

private fun Headers.Builder.addAll(extra: Map<String, String>) = apply {
    extra.forEach { (k, v) ->
        try { set(k, v) } catch (e: IllegalArgumentException) { throw McpException("Invalid header \"${k.take(40)}\"") }
    }
}

/** Parses a text/event-stream, calling [onEvent] with (event name, data) for each dispatched event. */
internal fun readSse(reader: BufferedReader, onEvent: (String, String) -> Unit) {
    var event = ""
    val data = StringBuilder()
    while (true) {
        val line = reader.readLine() ?: break
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) onEvent(event.ifEmpty { "message" }, data.toString().removeSuffix("\n"))
                event = ""; data.setLength(0)
            }
            line.startsWith(":") -> {}
            line.startsWith("event:") -> event = line.substring(6).trim()
            line.startsWith("data:") -> data.append(line.substring(5).removePrefix(" ")).append('\n')
        }
    }
    if (data.isNotEmpty()) onEvent(event.ifEmpty { "message" }, data.toString().removeSuffix("\n"))
}

/** MCP "Streamable HTTP": every message is a POST; the reply is JSON or an SSE stream. */
class StreamableHttpTransport(private val url: String, private val headers: Map<String, String>) : McpTransport {
    private val client = McpNet.client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    private var onMessage: (JSONObject) -> Unit = {}
    private var onClosed: (String) -> Unit = {}
    @Volatile private var session: String? = null
    @Volatile private var version: String? = null
    @Volatile private var closed = false

    override suspend fun start(onMessage: (JSONObject) -> Unit, onClosed: (String) -> Unit, onLog: (String) -> Unit) {
        McpNet.check(url)?.let { throw McpException(it) }
        this.onMessage = onMessage; this.onClosed = onClosed
    }

    override fun setProtocolVersion(version: String) { this.version = version }

    override suspend fun send(message: JSONObject) {
        val isInitialize = message.optString("method") == "initialize"
        val builder = Headers.Builder().add("Accept", "application/json, text/event-stream").addAll(headers)
        session?.let { builder.set("Mcp-Session-Id", it) }
        version?.let { builder.set("MCP-Protocol-Version", it) }
        val request = Request.Builder().url(url).headers(builder.build())
            .post(message.toString().toRequestBody(JSON_TYPE)).build()
        val call = client.newCall(request)
        calls += call
        val response = try {
            withContext(Dispatchers.IO) { call.execute() }
        } catch (e: IOException) {
            calls -= call
            throw McpException("Could not reach the server: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
        if (isInitialize) response.header("Mcp-Session-Id")?.let { session = it }
        if (!response.isSuccessful) {
            val body = runCatching { response.body?.string()?.take(300) }.getOrNull().orEmpty()
            response.close(); calls -= call
            if (isInitialize && response.code in listOf(400, 404, 405, 406, 415)) throw McpTransportUnsupported("HTTP ${response.code}")
            throw McpException(httpMessage(response.code, response.header("WWW-Authenticate"), body), response.code)
        }
        val type = response.header("Content-Type").orEmpty()
        when {
            response.code == 202 || response.body == null || response.body!!.contentLength() == 0L -> { response.close(); calls -= call }
            type.startsWith("text/event-stream") -> scope.launch {
                try {
                    BufferedReader(InputStreamReader(response.body!!.byteStream(), Charsets.UTF_8)).use { r ->
                        readSse(r) { _, data -> runCatching { JSONObject(data) }.getOrNull()?.let(onMessage) }
                    }
                } catch (_: IOException) {
                } finally { response.close(); calls -= call }
            }
            else -> withContext(Dispatchers.IO) {
                try {
                    val text = response.body!!.string()
                    val trimmed = text.trim()
                    if (trimmed.startsWith("[")) {
                        val arr = org.json.JSONArray(trimmed)
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(onMessage)
                    } else if (trimmed.startsWith("{")) onMessage(JSONObject(trimmed))
                    else if (isInitialize) throw McpTransportUnsupported("not JSON")
                } catch (e: org.json.JSONException) {
                    if (isInitialize) throw McpTransportUnsupported("not JSON") else throw McpException("The server sent an unreadable reply")
                } finally { response.close(); calls -= call }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val sid = session
        if (sid != null) {
            Thread {
                runCatching {
                    val b = Headers.Builder().addAll(headers).set("Mcp-Session-Id", sid)
                    client.newCall(Request.Builder().url(url).headers(b.build()).delete().build()).execute().close()
                }
            }.apply { isDaemon = true }.start()
        }
        calls.forEach { it.cancel() }
        scope.cancel()
    }
}

/** The older HTTP+SSE transport: GET opens an event stream that announces a POST endpoint. */
class LegacySseTransport(private val url: String, private val headers: Map<String, String>) : McpTransport {
    private val client = McpNet.client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var endpoint: String? = null
    private var stream: Call? = null
    @Volatile private var closed = false

    override suspend fun start(onMessage: (JSONObject) -> Unit, onClosed: (String) -> Unit, onLog: (String) -> Unit) {
        McpNet.check(url)?.let { throw McpException(it) }
        val request = Request.Builder().url(url)
            .headers(Headers.Builder().add("Accept", "text/event-stream").addAll(headers).build()).get().build()
        val call = client.newCall(request).also { stream = it }
        val ready = CompletableDeferred<String>()
        scope.launch {
            try {
                val response: Response = call.execute()
                if (!response.isSuccessful) {
                    val body = runCatching { response.body?.string()?.take(300) }.getOrNull().orEmpty()
                    response.close()
                    ready.completeExceptionally(McpException(httpMessage(response.code, response.header("WWW-Authenticate"), body), response.code))
                    return@launch
                }
                BufferedReader(InputStreamReader(response.body!!.byteStream(), Charsets.UTF_8)).use { r ->
                    readSse(r) { event, data ->
                        if (event == "endpoint") ready.complete(data.trim())
                        else runCatching { JSONObject(data) }.getOrNull()?.let(onMessage)
                    }
                }
                if (!ready.isCompleted) ready.completeExceptionally(McpException("The server did not announce a message endpoint"))
                if (!closed) { closed = true; onClosed("Connection closed by the server") }
            } catch (e: IOException) {
                if (!ready.isCompleted) ready.completeExceptionally(McpException("Could not reach the server: ${e.message ?: e.javaClass.simpleName}", cause = e))
                else if (!closed) { closed = true; onClosed("Connection lost: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
        val raw = try { withTimeout(15_000) { ready.await() } } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            close(); throw McpException("The server did not answer in time")
        }
        val resolved = runCatching { URI(url).resolve(raw) }.getOrNull() ?: throw McpException("The server sent a bad endpoint")
        val base = URI(url)
        if (resolved.host?.lowercase() != base.host?.lowercase() || resolved.scheme != base.scheme)
            throw McpException("The server pointed to a different host, refusing to follow it")
        endpoint = resolved.toString()
    }

    override suspend fun send(message: JSONObject) {
        val target = endpoint ?: throw McpException("Not connected")
        val request = Request.Builder().url(target).headers(Headers.Builder().addAll(headers).build())
            .post(message.toString().toRequestBody(JSON_TYPE)).build()
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) throw McpException(httpMessage(r.code, r.header("WWW-Authenticate"), r.body?.string()?.take(300).orEmpty()), r.code)
                }
            } catch (e: IOException) {
                throw McpException("Could not reach the server: ${e.message ?: e.javaClass.simpleName}", cause = e)
            }
        }
    }

    override fun close() {
        closed = true
        stream?.cancel()
        scope.cancel()
    }
}

internal fun httpMessage(code: Int, authenticate: String?, body: String): String = when (code) {
    401 -> "The server requires authentication (HTTP 401). Add an Authorization header."
    403 -> "The server refused access (HTTP 403)."
    404 -> "Nothing found at that URL (HTTP 404). Check the path, it is often /mcp or /sse."
    429 -> "The server is rate limiting requests (HTTP 429)."
    else -> "HTTP $code" + body.lineSequence().firstOrNull { it.isNotBlank() }?.let { ": ${it.take(160)}" }.orEmpty()
}
