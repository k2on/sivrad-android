package com.sivrad.assistant.settings

import android.content.Context
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
        /** Hugging Face (or any HTTPS) URL of the GGUF to download. */
        val llmUrl: String,
        /** Expected SHA-256 of that file, hex; blank skips verification. */
        val llmSha256: String,
        val llmThreads: Int,
    )

    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values.asStateFlow()

    private fun read() = Values(
        httpAllowlist = prefs.getString(KEY_ALLOWLIST, "")!!.lines().map { it.trim() }.filter { it.isNotEmpty() },
        llmUrl = prefs.getString(KEY_LLM_URL, null) ?: DEFAULT_LLM_URL,
        llmSha256 = prefs.getString(KEY_LLM_SHA, null) ?: DEFAULT_LLM_SHA256,
        llmThreads = prefs.getInt(KEY_THREADS, 4),
    )

    fun update(transform: (Values) -> Values) {
        val v = transform(_values.value)
        prefs.edit()
            .putString(KEY_ALLOWLIST, v.httpAllowlist.joinToString("\n"))
            .putString(KEY_LLM_URL, v.llmUrl.trim())
            .putString(KEY_LLM_SHA, v.llmSha256.trim().lowercase())
            .putInt(KEY_THREADS, v.llmThreads.coerceIn(1, 8))
            .apply()
        _values.value = read()
    }

    companion object {
        private const val KEY_ALLOWLIST = "http_allowlist"
        private const val KEY_LLM_URL = "llm_url"
        private const val KEY_LLM_SHA = "llm_sha256"
        private const val KEY_THREADS = "llm_threads"

        const val DEFAULT_LLM_URL =
            "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/a06e946bb6b655725eafa393f4a9745d460374c9/Qwen3-4B-Instruct-2507-Q4_K_M.gguf"
        const val DEFAULT_LLM_SHA256 = "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597"
    }
}
