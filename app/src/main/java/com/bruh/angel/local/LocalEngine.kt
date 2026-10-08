package com.bruh.angel.local

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Owns the single loaded on-device model. Every native call runs on one dedicated thread, so calls
 * are serialized as llama.cpp requires; [cancel] is the only call allowed from other threads.
 */
object LocalEngine {
    sealed interface State {
        data object Idle : State
        data class Loading(val name: String) : State
        data class Ready(val modelId: String, val name: String, val info: String) : State
        data class Failed(val message: String) : State
    }

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "angel-llama").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var handle = 0L
    private var loadedKey: String? = null
    private var linkedFd: ParcelFileDescriptor? = null
    private var systemInfo: String? = null

    /** Loads (or reuses) [model] with [params] and returns the native handle. */
    suspend fun ensureLoaded(context: Context, model: LocalModel, params: LocalParams): Long = withContext(dispatcher) {
        val key = "${model.id}|${params.contextSize}|${params.resolvedThreads()}"
        if (handle != 0L && key == loadedKey) return@withContext handle
        unloadLocked()
        check(model.ready) { "${model.name} isn't ready yet (${model.status.name.lowercase()})." }
        _state.value = State.Loading(model.name)
        try {
            initNative(context)
            checkMemory(context, model)
            val path = when (model.source) {
                LocalModel.Source.FILE -> {
                    val file = File(model.location)
                    check(file.isFile) { "The model file is missing: ${file.name}" }
                    file.path
                }
                LocalModel.Source.LINKED -> {
                    // Load in place via the document's file descriptor: no copy of a multi-GB file.
                    val fd = context.contentResolver.openFileDescriptor(Uri.parse(model.location), "r")
                        ?: error("Can't open ${model.name}. Re-add it from Local models.")
                    linkedFd = fd
                    "/proc/self/fd/${fd.fd}"
                }
            }
            handle = LlamaNative.load(path, params.contextSize, params.resolvedThreads())
            loadedKey = key
            _state.value = State.Ready(model.id, model.name, LlamaNative.info(handle))
            handle
        } catch (e: SecurityException) {
            unloadLocked()
            fail("Android revoked access to ${model.name}. Re-add the file from Local models.")
        } catch (e: Throwable) {
            unloadLocked()
            fail(e.message ?: "Could not load ${model.name}")
        }
    }

    private fun fail(message: String): Nothing {
        _state.value = State.Failed(message)
        throw IllegalStateException(message)
    }

    /** Runs [block] with the loaded handle on the engine thread. */
    suspend fun <T> withHandle(block: (Long) -> T): T = withContext(dispatcher) {
        check(handle != 0L) { "No local model is loaded" }
        block(handle)
    }

    /** Thread-safe: asks the running prompt evaluation/generation to stop soon. */
    fun cancel() {
        val h = handle
        if (h != 0L) LlamaNative.cancel(h)
    }

    suspend fun unload() = withContext(dispatcher) {
        unloadLocked()
        _state.value = State.Idle
    }

    /** Whether [modelId] is the one currently loaded. */
    fun isLoaded(modelId: String): Boolean = (state.value as? State.Ready)?.modelId == modelId

    fun systemInfo(context: Context): String? = runCatching { initNative(context) }.getOrNull()

    private fun unloadLocked() {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
        }
        loadedKey = null
        runCatching { linkedFd?.close() }
        linkedFd = null
    }

    @Synchronized
    private fun initNative(context: Context): String {
        systemInfo?.let { return it }
        try {
            System.loadLibrary("angel_llama")
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("On-device models need a 64-bit ARM phone (native library missing: ${e.message}).")
        }
        val info = LlamaNative.initBackend(context.applicationInfo.nativeLibraryDir)
        check(!info.startsWith("backends=0")) { "llama.cpp found no CPU backend for this processor." }
        systemInfo = info
        return info
    }

    /** Refuse models that clearly can't fit; Android would kill the app mid-load otherwise. */
    private fun checkMemory(context: Context, model: LocalModel) {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val total = memory.totalMem
        if (model.size > 0 && model.size > total * 0.7) {
            throw IllegalStateException(
                "${model.name} is ${formatBytes(model.size)} but this phone has ${formatBytes(total)} RAM. " +
                    "Pick a smaller model or a lower quantization (e.g. Q4_K_M of a smaller size)."
            )
        }
    }

    /** Parsed `info()` JSON for display. */
    fun describe(info: String): String = runCatching {
        val o = JSONObject(info)
        "${o.optString("desc")} · context ${o.optInt("ctx")} of ${o.optInt("ctxTrain")}" +
            if (o.optBoolean("thinking")) " · supports thinking" else ""
    }.getOrDefault(info)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> "$bytes B"
}

