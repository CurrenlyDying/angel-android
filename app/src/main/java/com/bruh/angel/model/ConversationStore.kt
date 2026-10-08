package com.bruh.angel.model

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

data class ConversationMeta(
    val id: String,
    val title: String,
    val updated: Long,
    val provider: String,
    val model: String,
    val messages: Int
)

data class Conversation(
    val id: String,
    val title: String,
    val updated: Long,
    val provider: Provider,
    val model: String,
    val items: List<ChatItem>,
    /** Provider-native model context, restorable only with the same provider and model. */
    val history: String?
)

/**
 * Saved chats in app-private storage (backups are disabled for this app). One JSON file per
 * conversation plus a small index for the history list. Keeps the newest [MAX] conversations.
 */
class ConversationStore(context: Context) {
    private val dir = File(context.filesDir, "conversations")
    private val index = AtomicFile(File(dir, "index.json"))

    private fun file(id: String): AtomicFile {
        require(id.matches(ID)) { "Invalid conversation id" }
        return AtomicFile(File(dir, "$id.json"))
    }

    private fun writeAtomic(target: AtomicFile, text: String) {
        val stream = target.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    fun list(): List<ConversationMeta> {
        val text = runCatching { String(index.readFully(), Charsets.UTF_8) }.getOrNull() ?: return rebuildIndex()
        return runCatching { decodeIndex(JSONArray(text)) }.getOrElse { rebuildIndex() }
    }

    @Synchronized
    fun save(conversation: Conversation) {
        dir.mkdirs()
        writeAtomic(file(conversation.id), encode(conversation).toString())
        val metas = (listOf(conversation.meta()) + list().filter { it.id != conversation.id })
            .sortedByDescending { it.updated }
        metas.drop(MAX).forEach { file(it.id).delete() }
        writeIndex(metas.take(MAX))
    }

    @Synchronized
    fun load(id: String): Conversation? =
        runCatching { decode(JSONObject(String(file(id).readFully(), Charsets.UTF_8))) }.getOrNull()

    @Synchronized
    fun delete(id: String) {
        file(id).delete()
        writeIndex(list().filter { it.id != id })
    }

    @Synchronized
    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun writeIndex(metas: List<ConversationMeta>) {
        dir.mkdirs()
        writeAtomic(index, JSONArray().also { array ->
            metas.forEach {
                array.put(JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updated)
                    .put("provider", it.provider).put("model", it.model).put("messages", it.messages))
            }
        }.toString())
    }

    private fun rebuildIndex(): List<ConversationMeta> {
        val metas = dir.listFiles { f -> f.name.endsWith(".json") && f.name != "index.json" }.orEmpty()
            .mapNotNull { f -> runCatching { decode(JSONObject(String(AtomicFile(f).readFully(), Charsets.UTF_8))).meta() }.getOrNull() }
            .sortedByDescending { it.updated }
        if (dir.isDirectory) runCatching { writeIndex(metas) }
        return metas
    }

    private fun decodeIndex(array: JSONArray): List<ConversationMeta> = (0 until array.length()).mapNotNull { i ->
        array.optJSONObject(i)?.let {
            ConversationMeta(it.getString("id"), it.optString("title"), it.optLong("updated"),
                it.optString("provider"), it.optString("model"), it.optInt("messages"))
        }
    }

    companion object {
        const val MAX = 200
        private val ID = Regex("[A-Za-z0-9-]{8,64}")

        fun newId(): String = UUID.randomUUID().toString()

        fun Conversation.meta() = ConversationMeta(id, title, updated, provider.label, model,
            items.count { it is ChatItem.UserMsg || it is ChatItem.AgentMsg })

        fun encodeItems(items: List<ChatItem>): JSONArray = JSONArray().also { array ->
            items.forEach { item ->
                array.put(when (item) {
                    is ChatItem.UserMsg -> JSONObject().put("t", "user").put("text", item.text)
                    is ChatItem.AgentMsg -> JSONObject().put("t", "agent").put("text", item.text)
                    is ChatItem.ToolCall -> JSONObject().put("t", "tool").put("command", item.command)
                        .put("output", item.output).put("exitCode", item.exitCode).put("running", item.running)
                })
            }
        }

        fun decodeItems(array: JSONArray): List<ChatItem> = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            when (o.optString("t")) {
                "user" -> ChatItem.UserMsg(o.optString("text"))
                "agent" -> ChatItem.AgentMsg(o.optString("text"))
                "tool" -> ChatItem.ToolCall(o.optString("command"), o.optString("output"), o.optInt("exitCode"), o.optBoolean("running"))
                else -> null
            }
        }

        fun encode(c: Conversation): JSONObject = JSONObject()
            .put("id", c.id).put("title", c.title).put("updated", c.updated)
            .put("provider", c.provider.name).put("model", c.model)
            .put("items", encodeItems(c.items))
            .put("history", c.history ?: JSONObject.NULL)

        fun decode(o: JSONObject): Conversation = Conversation(
            id = o.getString("id"),
            title = o.optString("title"),
            updated = o.optLong("updated"),
            provider = runCatching { Provider.valueOf(o.optString("provider")) }.getOrDefault(Provider.OPENAI),
            model = o.optString("model"),
            items = decodeItems(o.optJSONArray("items") ?: JSONArray()),
            history = if (o.isNull("history")) null else o.optString("history")
        )

        /** Plain-text export for sharing. */
        fun transcript(c: Conversation): String = buildString {
            append("${c.title}\n${c.provider.label} · ${c.model} · ${DateFormat.getDateTimeInstance().format(Date(c.updated))}\n\n")
            c.items.forEach { item ->
                when (item) {
                    is ChatItem.UserMsg -> append("You:\n${item.text}\n\n")
                    is ChatItem.AgentMsg -> append("Angel:\n${item.text}\n\n")
                    is ChatItem.ToolCall -> append("$ ${item.command}\n${item.output.trimEnd()}\n[exit ${item.exitCode}]\n\n")
                }
            }
        }
    }
}

