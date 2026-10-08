package com.bruh.angel.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A GGUF model the user added: a file in app storage, or a linked document (no copy). */
data class LocalModel(
    val id: String,
    val name: String,
    val source: Source,
    /** Absolute path for FILE, content:// URI for LINKED. */
    val location: String,
    val size: Long,
    val status: Status = Status.READY,
    val repo: String? = null,
    val sha256: String? = null,
    val downloadId: Long = -1,
    val error: String? = null,
    val added: Long = System.currentTimeMillis()
) {
    enum class Source { FILE, LINKED }
    enum class Status { DOWNLOADING, VERIFYING, READY, FAILED }

    val ready get() = status == Status.READY
}

/** Inference settings for on-device models. */
data class LocalParams(
    val contextSize: Int = 4096,
    /** 0 = automatic. */
    val threads: Int = 0,
    val temperature: Float = 0.6f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val maxTokens: Int = 2048,
    /** Let reasoning models think before answering (slower, often better). */
    val thinking: Boolean = false
) {
    fun resolvedThreads(): Int = if (threads > 0) threads else (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    fun toJson(): JSONObject = JSONObject()
        .put("contextSize", contextSize).put("threads", threads).put("temperature", temperature.toDouble())
        .put("topP", topP.toDouble()).put("topK", topK).put("minP", minP.toDouble())
        .put("maxTokens", maxTokens).put("thinking", thinking)

    companion object {
        fun fromJson(o: JSONObject?): LocalParams {
            if (o == null) return LocalParams()
            val d = LocalParams()
            return LocalParams(
                contextSize = o.optInt("contextSize", d.contextSize).coerceIn(512, 131072),
                threads = o.optInt("threads", d.threads).coerceIn(0, 16),
                temperature = o.optDouble("temperature", d.temperature.toDouble()).toFloat().coerceIn(0f, 2f),
                topP = o.optDouble("topP", d.topP.toDouble()).toFloat().coerceIn(0.05f, 1f),
                topK = o.optInt("topK", d.topK).coerceIn(0, 1000),
                minP = o.optDouble("minP", d.minP.toDouble()).toFloat().coerceIn(0f, 1f),
                maxTokens = o.optInt("maxTokens", d.maxTokens).coerceIn(64, 32768),
                thinking = o.optBoolean("thinking", d.thinking)
            )
        }
    }
}

/** Registry of local models (app-private JSON), observable for the UI. One instance per process. */
class LocalModelStore private constructor(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "local-models.json"))
    private val _models = MutableStateFlow(read())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    /** Where downloads go: app-specific external storage (no permission needed, removed on uninstall). */
    val directory: File
        get() = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    fun get(id: String): LocalModel? = _models.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(model: LocalModel) {
        val list = _models.value.filter { it.id != model.id } + model
        write(list)
        _models.value = list
    }

    @Synchronized
    fun update(id: String, change: (LocalModel) -> LocalModel) {
        val current = get(id) ?: return
        upsert(change(current))
    }

    /** Removes the entry; deletes the file only if it lives in our own models directory. */
    @Synchronized
    fun remove(id: String) {
        val model = get(id) ?: return
        when (model.source) {
            LocalModel.Source.FILE -> {
                val f = File(model.location)
                if (f.canonicalPath.startsWith(directory.canonicalPath + File.separator)) f.delete()
            }
            LocalModel.Source.LINKED -> runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(model.location), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val list = _models.value.filter { it.id != id }
        write(list)
        _models.value = list
    }

    private fun read(): List<LocalModel> = runCatching {
        val array = JSONArray(String(file.readFully(), Charsets.UTF_8))
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::decode) }
    }.getOrDefault(emptyList())

    private fun write(list: List<LocalModel>) {
        val array = JSONArray().also { a -> list.forEach { a.put(encode(it)) } }
        val stream = file.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun encode(m: LocalModel) = JSONObject()
        .put("id", m.id).put("name", m.name).put("source", m.source.name).put("location", m.location)
        .put("size", m.size).put("status", m.status.name).put("repo", m.repo ?: JSONObject.NULL)
        .put("sha256", m.sha256 ?: JSONObject.NULL).put("downloadId", m.downloadId)
        .put("error", m.error ?: JSONObject.NULL).put("added", m.added)

    private fun decode(o: JSONObject) = LocalModel(
        id = o.getString("id"),
        name = o.optString("name"),
        source = runCatching { LocalModel.Source.valueOf(o.optString("source")) }.getOrDefault(LocalModel.Source.FILE),
        location = o.optString("location"),
        size = o.optLong("size"),
        status = runCatching { LocalModel.Status.valueOf(o.optString("status")) }.getOrDefault(LocalModel.Status.FAILED),
        repo = o.optString("repo").takeUnless { o.isNull("repo") || it.isEmpty() },
        sha256 = o.optString("sha256").takeUnless { o.isNull("sha256") || it.isEmpty() },
        downloadId = o.optLong("downloadId", -1),
        error = o.optString("error").takeUnless { o.isNull("error") || it.isEmpty() },
        added = o.optLong("added")
    )

    companion object {
        @Volatile private var instance: LocalModelStore? = null

        fun get(context: Context): LocalModelStore = instance ?: synchronized(this) {
            instance ?: LocalModelStore(context.applicationContext).also { instance = it }
        }

        fun newId(): String = UUID.randomUUID().toString()

        /** GGUF files start with the ASCII magic "GGUF". */
        fun isGguf(header: ByteArray): Boolean =
            header.size >= 4 && header[0] == 'G'.code.toByte() && header[1] == 'G'.code.toByte() &&
                header[2] == 'U'.code.toByte() && header[3] == 'F'.code.toByte()

        /** Quantization tag from a file name, e.g. "Q4_K_M", "Q8_0", "BF16". */
        fun quantOf(name: String): String? =
            Regex("(?i)(IQ\\d_[A-Z]+|Q\\d+_K(_[SMLXL]+)?|Q\\d+_\\d|Q\\d+_K|BF16|F16|F32|TQ\\d_\\d|MXFP4)").findAll(name).lastOrNull()?.value?.uppercase()
    }
}

