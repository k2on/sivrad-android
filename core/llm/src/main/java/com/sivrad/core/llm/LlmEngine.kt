package com.sivrad.core.llm

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

data class LlmConfig(
    val modelPath: File,
    val contextSize: Int = 4096,
    // Tensor G3: 1x X3 + 4x A715 + 4x A510. Four threads keeps generation on
    // the big cores. TODO(on-device): tune both against tokens/s and heat.
    val threads: Int = 4,
    val batchThreads: Int = 6,
)

data class Sampling(
    // Qwen3-Instruct-2507's recommended settings.
    val temperature: Float = 0.7f,
    val topP: Float = 0.8f,
    val topK: Int = 20,
    val minP: Float = 0.0f,
    val seed: Int = 0,
) {
    internal fun toArray() = floatArrayOf(temperature, topP, topK.toFloat(), minP, seed.toFloat())
}

/**
 * The model, loaded once and kept resident. All native work runs on one
 * dedicated thread — never the main thread — which also serialises access to
 * the llama context.
 */
class LlmEngine(val config: LlmConfig) {
    private val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llm-inference")
    }.asCoroutineDispatcher()

    @Volatile
    private var handle = 0L

    val isLoaded: Boolean get() = handle != 0L

    suspend fun load() = withContext(dispatcher) {
        if (handle != 0L) return@withContext
        require(config.modelPath.isFile) { "model file not found: ${config.modelPath}" }
        backendOnce
        val start = System.nanoTime()
        handle = LlamaNative.load(
            config.modelPath.absolutePath.toByteArray(),
            config.contextSize, config.threads, config.batchThreads,
        )
        Log.i(TAG, "model loaded in ${(System.nanoTime() - start) / 1_000_000} ms")
    }

    suspend fun unload() = withContext(dispatcher) {
        if (handle != 0L) LlamaNative.free(handle)
        handle = 0L
    }

    /**
     * Renders [messages] with the chat template embedded in the GGUF. Falls
     * back to ChatML (which Qwen uses) if llama.cpp does not recognise it.
     */
    suspend fun render(messages: List<ChatMessage>, addAssistant: Boolean = true): String =
        withContext(dispatcher) {
            val h = requireHandle()
            LlamaNative.applyTemplate(
                h,
                messages.map { it.role.wire.toByteArray() }.toTypedArray(),
                messages.map { it.content.toByteArray() }.toTypedArray(),
                addAssistant,
            )?.let { String(it, Charsets.UTF_8) } ?: chatMl(messages, addAssistant)
        }

    /** Evaluates [prompt] into the KV cache without generating, so the next turn starts warm. */
    suspend fun prefill(prompt: String) = withContext(dispatcher) {
        LlamaNative.generate(requireHandle(), prompt.toByteArray(), null, 0, Sampling().toArray()) { true }
    }

    /**
     * Streams the completion of [prompt] as text pieces. [grammar] (GBNF,
     * `root` rule) constrains the output. Cancelling the collector stops
     * generation at the next token.
     */
    fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int = 512,
        sampling: Sampling = Sampling(),
    ): Flow<String> = callbackFlow {
        val h = requireHandle()
        val job = launch(dispatcher) {
            LlamaNative.generate(
                h, prompt.toByteArray(), grammar?.toByteArray(), maxTokens, sampling.toArray(),
            ) { bytes -> trySend(String(bytes, Charsets.UTF_8)).isSuccess && isActive }
            channel.close()
        }
        awaitClose { if (job.isActive) LlamaNative.abort(h) }
    }.buffer(Channel.UNLIMITED)

    private fun requireHandle(): Long = handle.also { check(it != 0L) { "model not loaded" } }

    private companion object {
        const val TAG = "LlmEngine"
        val backendOnce: Unit by lazy { LlamaNative.backendInit() }

        fun chatMl(messages: List<ChatMessage>, addAssistant: Boolean) = buildString {
            for (m in messages) append("<|im_start|>${m.role.wire}\n${m.content}<|im_end|>\n")
            if (addAssistant) append("<|im_start|>assistant\n")
        }
    }
}
