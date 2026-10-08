package com.bruh.angel.agent

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.bruh.angel.local.LlamaNative
import com.bruh.angel.local.LocalEngine
import com.bruh.angel.local.LocalModel
import com.bruh.angel.local.LocalParams
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * On-device model via llama.cpp. History is kept in OpenAI chat format; llama.cpp renders it with the
 * model's own chat template (including its native tool-call format) and parses tool calls back out.
 */
class LocalModelSession(
    private val appContext: Context,
    private val model: LocalModel,
    private val params: LocalParams,
    context: String = "",
    restoredHistory: String? = null,
    private val external: ExternalTools = ExternalTools.NONE
) : ChatModel {
    private val history: JSONArray = restoredHistory?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
    private val system = ModelSession.SYSTEM_PROMPT + "\n\n" + LOCAL_NOTE.format(model.name) +
        if (context.isBlank()) "" else "\n\n" + context.trim()

    override val isEmpty: Boolean get() = history.length() == 0

    override fun exportHistory(): String = history.toString()

    override fun user(text: String) {
        history.put(obj("role" to "user", "content" to text))
    }

    override fun results(results: List<Pair<RequestedTool, String>>) {
        results.forEach { (tool, output) ->
            // Small local contexts: keep each tool result to ~3k tokens.
            val trimmed = if (output.length > MAX_TOOL_CHARS) output.take(MAX_TOOL_CHARS) + "\n[output truncated]" else output
            history.put(obj("role" to "tool", "tool_call_id" to tool.id, "name" to tool.name, "content" to trimmed))
        }
    }

    override suspend fun next(onPartial: (String) -> Unit): ModelReply {
        LocalEngine.ensureLoaded(appContext, model, params)
        val job = currentCoroutineContext()[Job]
        val messages = JSONArray().put(obj("role" to "system", "content" to system))
        for (i in 0 until history.length()) messages.put(history.get(i))
        val tools = JSONArray(toolDeclarations(external.list().take(MAX_EXTERNAL)).map { obj("type" to "function", "function" to it) })

        val (message, stats) = LocalEngine.withHandle { handle ->
            val prompt = LlamaNative.prepare(handle, messages.toString().toByteArray(), tools.toString().toByteArray(), params.thinking)
            val raw = ByteArrayOutputStream()
            var lastShown = 0L
            val output = LlamaNative.generate(
                handle, prompt, params.maxTokens, params.temperature, params.topP, params.topK, params.minP, -1
            ) { bytes ->
                raw.write(bytes)
                val now = SystemClock.uptimeMillis()
                if (now - lastShown > STREAM_INTERVAL_MS) {
                    lastShown = now
                    onPartial(visibleText(parse(handle, raw.toByteArray(), partial = true)))
                }
                job?.isActive != false
            }
            parse(handle, output, partial = false) to JSONObject(LlamaNative.stats(handle))
        }
        Log.i(TAG, "local reply: $stats")

        val text = message.optString("content").takeUnless { message.isNull("content") }.orEmpty().trim()
        val calls = mutableListOf<RequestedTool>()
        val historyCalls = JSONArray()
        val toolCalls = message.optJSONArray("tool_calls") ?: JSONArray()
        for (i in 0 until toolCalls.length()) {
            val call = toolCalls.optJSONObject(i) ?: continue
            val function = call.optJSONObject("function") ?: continue
            val id = call.optString("id").ifBlank { "call_${history.length()}_$i" }
            val rawArgs = function.optString("arguments", "{}")
            val args = runCatching { JSONObject(rawArgs.ifBlank { "{}" }) }
                .getOrElse { throw ProviderException("The local model produced malformed tool arguments.") }
            calls += validatedToolCall(id, function.optString("name"), args)
            historyCalls.put(obj("id" to id, "type" to "function",
                "function" to obj("name" to function.optString("name"), "arguments" to args.toString())))
            if (calls.size >= 4) break
        }
        // Reasoning is not kept in history: it is large and templates discard earlier thinking anyway.
        history.put(JSONObject().put("role", "assistant").put("content", text).apply {
            if (historyCalls.length() > 0) put("tool_calls", historyCalls)
        })

        val note = when (stats.optString("stop")) {
            "length" -> "\n\n_[Reply cut at the ${params.maxTokens}-token limit.]_"
            "context" -> "\n\n_[Context full: start a new chat or raise the context size in Local models.]_"
            else -> ""
        }
        val visible = when {
            text.isNotEmpty() -> text + note
            calls.isEmpty() -> "(The model returned an empty reply.)$note"
            else -> note.trim()
        }
        return ModelReply(visible, calls)
    }

    private fun parse(handle: Long, raw: ByteArray, partial: Boolean): JSONObject =
        runCatching { JSONObject(String(LlamaNative.parse(handle, raw, partial), Charsets.UTF_8)) }
            .getOrElse { JSONObject().put("content", String(raw, Charsets.UTF_8)) }

    private fun visibleText(partial: JSONObject): String {
        val content = partial.optString("content").takeUnless { partial.isNull("content") }.orEmpty()
        return when {
            content.isNotBlank() -> content
            partial.optJSONArray("tool_calls")?.length() ?: 0 > 0 -> "Preparing a tool call…"
            partial.optString("reasoning_content").isNotBlank() -> "Thinking…"
            else -> ""
        }
    }

    private companion object {
        const val TAG = "AngelLocal"
        const val MAX_TOOL_CHARS = 12_000
        const val MAX_EXTERNAL = 12
        const val STREAM_INTERVAL_MS = 150L
        const val LOCAL_NOTE = "You are running fully on this phone as the local model \"%s\" (no internet needed for you to think). " +
            "When you need a tool, call it using your native tool-calling format, one tool at a time, then wait for its result."
    }
}

