package com.sivrad.assistant.settings

import android.content.Context
import com.sivrad.assistant.models.ModelSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User settings, in device-protected storage so the assistant can read them
 * before the first unlock.
 */
class AppSettings(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    data class Values(
        val httpAllowlist: List<String>,
        /** Selected [com.sivrad.assistant.models.ModelOption] ids, per slot. */
        val streamingModel: String,
        val refinerModel: String,
        val llmModel: String,
        /** A GGUF of the user's choosing, offered as an extra language model. */
        val customLlmUrl: String,
        /** Expected SHA-256 of that file, hex; blank skips verification. */
        val customLlmSha256: String,
        val customLlmNoThinking: Boolean,
        val llmThreads: Int,
        /** Show token rates and timings under each reply. */
        val showStats: Boolean,
    )

    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values.asStateFlow()

    private fun read() = Values(
        httpAllowlist = prefs.getString(KEY_ALLOWLIST, "")!!.lines().map { it.trim() }.filter { it.isNotEmpty() },
        streamingModel = prefs.getString(KEY_STREAMING, null) ?: defaultFor(ModelSlot.Streaming),
        refinerModel = prefs.getString(KEY_REFINER, null) ?: defaultFor(ModelSlot.Refiner),
        llmModel = prefs.getString(KEY_LLM, null) ?: defaultFor(ModelSlot.Llm),
        customLlmUrl = prefs.getString(KEY_CUSTOM_URL, "")!!,
        customLlmSha256 = prefs.getString(KEY_CUSTOM_SHA, "")!!,
        customLlmNoThinking = prefs.getBoolean(KEY_CUSTOM_NO_THINK, false),
        llmThreads = prefs.getInt(KEY_THREADS, 4),
        showStats = prefs.getBoolean(KEY_STATS, true),
    )

    fun update(transform: (Values) -> Values) {
        val v = transform(_values.value)
        prefs.edit()
            .putString(KEY_ALLOWLIST, v.httpAllowlist.joinToString("\n"))
            .putString(KEY_STREAMING, v.streamingModel)
            .putString(KEY_REFINER, v.refinerModel)
            .putString(KEY_LLM, v.llmModel)
            .putString(KEY_CUSTOM_URL, v.customLlmUrl.trim())
            .putString(KEY_CUSTOM_SHA, v.customLlmSha256.trim().lowercase())
            .putBoolean(KEY_CUSTOM_NO_THINK, v.customLlmNoThinking)
            .putInt(KEY_THREADS, v.llmThreads.coerceIn(1, 8))
            .putBoolean(KEY_STATS, v.showStats)
            .apply()
        _values.value = read()
    }

    fun select(slot: ModelSlot, id: String) = update {
        when (slot) {
            ModelSlot.Streaming -> it.copy(streamingModel = id)
            ModelSlot.Refiner -> it.copy(refinerModel = id)
            ModelSlot.Llm -> it.copy(llmModel = id)
        }
    }

    companion object {
        private const val KEY_ALLOWLIST = "http_allowlist"
        private const val KEY_STREAMING = "model_streaming"
        private const val KEY_REFINER = "model_refiner"
        private const val KEY_LLM = "model_llm"
        private const val KEY_CUSTOM_URL = "custom_llm_url"
        private const val KEY_CUSTOM_SHA = "custom_llm_sha256"
        private const val KEY_CUSTOM_NO_THINK = "custom_llm_no_thinking"
        private const val KEY_THREADS = "llm_threads"
        private const val KEY_STATS = "show_stats"

        fun defaultFor(slot: ModelSlot) = when (slot) {
            ModelSlot.Streaming -> "kroko-en-2025-08-06"
            ModelSlot.Refiner -> "moonshine-base-en"
            ModelSlot.Llm -> "qwen3-4b-2507-q4_0"
        }
    }
}
