package com.sivrad.core.llm

/** Receives generated text. Return false to stop generation. */
fun interface TokenSink {
    fun onPiece(utf8: ByteArray): Boolean
}

/**
 * JNI surface of `llm_jni.cpp`. Strings travel as UTF-8 byte arrays (see the
 * note at the top of that file). Not thread-safe per handle: [LlmEngine] only
 * calls in from its single inference thread, except [abort].
 */
internal object LlamaNative {
    init {
        System.loadLibrary("sivrad_llm")
    }

    external fun backendInit()

    /** Returns an opaque handle; throws IllegalStateException on failure. */
    external fun load(modelPath: ByteArray, nCtx: Int, nThreads: Int, nThreadsBatch: Int): Long

    external fun free(handle: Long)

    /** Safe from any thread: makes the running [generate] return early. */
    external fun abort(handle: Long)

    external fun contextSize(handle: Long): Int

    /** {promptTokens, reusedTokens, prefillMs, generatedTokens, generateMs} of the last [generate]. */
    external fun lastStats(handle: Long): FloatArray

    /** Null if the model has no chat template llama.cpp understands. */
    external fun applyTemplate(
        handle: Long,
        roles: Array<ByteArray>,
        contents: Array<ByteArray>,
        addAssistant: Boolean,
    ): ByteArray?

    /** sampling = {temperature, topP, topK, minP, seed}. */
    external fun generate(
        handle: Long,
        prompt: ByteArray,
        grammar: ByteArray?,
        maxTokens: Int,
        sampling: FloatArray,
        sink: TokenSink,
    ): Int
}
