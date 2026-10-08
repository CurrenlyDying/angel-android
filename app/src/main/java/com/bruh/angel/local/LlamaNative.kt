package com.bruh.angel.local

/**
 * JNI surface of libangel_llama.so (app/src/main/cpp/angel_llama.cpp). All calls for one handle must be
 * serialized; [LocalEngine] runs them on a single thread. Text that may contain user content crosses
 * the boundary as UTF-8 bytes.
 */
object LlamaNative {
    /** Receives raw UTF-8 bytes of each generated token; return false to stop generating. */
    fun interface TokenListener {
        fun onToken(bytes: ByteArray): Boolean
    }

    @JvmStatic external fun initBackend(nativeLibDir: String): String
    @JvmStatic external fun load(path: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun info(handle: Long): String
    @JvmStatic external fun prepare(handle: Long, messages: ByteArray, tools: ByteArray?, enableThinking: Boolean): ByteArray
    @JvmStatic external fun generate(
        handle: Long, prompt: ByteArray, maxTokens: Int,
        temperature: Float, topP: Float, topK: Int, minP: Float, seed: Int,
        listener: TokenListener?
    ): ByteArray
    @JvmStatic external fun parse(handle: Long, raw: ByteArray, partial: Boolean): ByteArray
    @JvmStatic external fun cancel(handle: Long)
    @JvmStatic external fun clearCache(handle: Long)
    @JvmStatic external fun stats(handle: Long): String
}

