package com.bruh.angel.local

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the single loaded on-device model. Every native call runs on one dedicated thread, so calls
 * are serialized as llama.cpp requires; [cancel] is the only call allowed from other threads.
 */
object LocalEngine {
    sealed interface State {
        data object Idle : State
        data class Loading(val name: String, val progress: Int? = null) : State
        data class Ready(val modelId: String, val name: String, val info: String) : State
        data class Failed(val message: String) : State
    }

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "angel-llama").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var handle = 0L
    private val handleLock = Any()
    private var loadedKey: String? = null
    private var systemInfo: String? = null
    @Volatile private var loadingCancel: AtomicBoolean? = null

    /** Loads (or reuses) [model] with [params] and returns the native handle. */
    suspend fun ensureLoaded(context: Context, model: LocalModel, params: LocalParams, onLoadingProgress: (Int) -> Unit = {}): Long = withContext(dispatcher) {
        val key = "${model.id}|${params.contextSize}|${params.resolvedThreads()}"
        if (handle != 0L && key == loadedKey) return@withContext handle
        unloadLocked()
        check(model.ready) { "${model.name} isn't ready yet (${model.status.name.lowercase()})." }
        val loadJob = currentCoroutineContext().job
        val cancelled = AtomicBoolean(false)
        loadingCancel = cancelled
        _state.value = State.Loading(model.name)
        try {
            val checked = model.checkedFile()
            if (!checked.ready) {
                LocalModelStore.get(context).update(model.id) { it.copy(status = checked.status, error = checked.error) }
                error(checked.error ?: "The model file is unavailable")
            }
            initNative(context)
            checkMemory(context, model)
            val progress = LlamaNative.ProgressListener { done, total ->
                val percent = (done * 100 / total.coerceAtLeast(1)).coerceIn(0, 100)
                _state.value = State.Loading(model.name, percent)
                onLoadingProgress(percent)
                loadJob.isActive && !cancelled.get()
            }
            val loaded = when (model.source) {
                LocalModel.Source.FILE -> {
                    val file = File(model.location)
                    check(file.isFile) { "The model file is missing: ${file.name}" }
                    LlamaNative.load(file.path, params.contextSize, params.resolvedThreads(), progress = progress)
                }
                LocalModel.Source.LINKED -> {
                    // Load in place via the document's file descriptor: no copy of a multi-GB file.
                    val fd = context.contentResolver.openFileDescriptor(Uri.parse(model.location), "r")
                        ?: error("Can't open ${model.name}. Re-add it from Local models.")
                    fd.use { LlamaNative.load("", params.contextSize, params.resolvedThreads(), fd = it.fd, progress = progress) }
                }
            }
            if (!loadJob.isActive || cancelled.get()) {
                LlamaNative.free(loaded)
                throw CancellationException("Model loading stopped")
            }
            handle = loaded
            loadedKey = key
            _state.value = State.Ready(model.id, model.name, LlamaNative.info(handle))
            handle
        } catch (e: SecurityException) {
            unloadLocked()
            fail("Android revoked access to ${model.name}. Re-add the file from Local models.")
        } catch (e: Throwable) {
            unloadLocked()
            if (e is CancellationException || !loadJob.isActive || cancelled.get()) {
                _state.value = State.Idle
                throw CancellationException("Model loading stopped", e)
            }
            fail(e.message ?: "Could not load ${model.name}")
        } finally {
            loadingCancel = null
        }
    }

    private fun fail(message: String): Nothing {
        _state.value = State.Failed(message)
        throw IllegalStateException(message)
    }

    /** Runs [block] with the loaded handle on the engine thread. */
    suspend fun <T> withHandle(block: (Long) -> T): T = withContext(dispatcher) {
        val active = handle
        check(active != 0L) { "No local model is loaded" }
        // Reset before installing cancellation; a Stop arriving just before generate must survive.
        LlamaNative.resetCancel(active)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancelHandle(active) }
            if (continuation.isActive) {
                try { continuation.resume(block(active)) }
                catch (error: Throwable) { continuation.resumeWithException(error) }
            }
        }
    }

    /** Thread-safe: asks the running prompt evaluation/generation to stop soon. */
    fun cancel() {
        loadingCancel?.set(true)
        synchronized(handleLock) {
            val h = handle
            if (h != 0L) LlamaNative.cancel(h)
        }
    }

    private fun cancelHandle(expected: Long) = synchronized(handleLock) {
        if (handle == expected) LlamaNative.cancel(expected)
    }

    suspend fun unload() = withContext(dispatcher) {
        unloadLocked()
        _state.value = State.Idle
    }

    /** Whether [modelId] is the one currently loaded. */
    fun isLoaded(modelId: String): Boolean = (state.value as? State.Ready)?.modelId == modelId

    fun systemInfo(context: Context): String? = runCatching { initNative(context) }.getOrNull()

    private fun unloadLocked() {
        val old = synchronized(handleLock) { handle.also { handle = 0L } }
        if (old != 0L) LlamaNative.free(old)
        loadedKey = null
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

