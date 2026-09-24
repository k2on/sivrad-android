package com.sivrad.assistant

import android.util.Log
import com.sivrad.assistant.models.ModelCatalog
import com.sivrad.assistant.models.ModelOption
import com.sivrad.assistant.settings.AppSettings
import com.sivrad.core.audio.VadModel
import com.sivrad.core.llm.Agent
import com.sivrad.core.llm.LlmConfig
import com.sivrad.core.llm.LlmEngine
import com.sivrad.core.stt.OfflineTranscriber
import com.sivrad.core.stt.StreamingTranscriber
import com.sivrad.core.tools.ToolRegistry
import com.sivrad.core.tools.builtin.BuiltinTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The models, loaded once per process and kept resident so the overlay can
 * start listening the moment it appears. Loading is started by
 * AssistantService (bound by the system for as long as Sivrad is the
 * default assistant, which keeps this process alive) and awaited by sessions.
 */
class AssistantEngine(
    private val catalog: ModelCatalog,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    sealed interface Status {
        data object NotLoaded : Status
        data object MissingModels : Status
        data object Loading : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    class Loaded(
        val transcriber: StreamingTranscriber,
        val vad: VadModel,
        val llm: LlmEngine,
        /** Short names of the loaded models, for the overlay's stats line. */
        val llmName: String,
        val refinerName: String?,
    )

    val registry = ToolRegistry(BuiltinTools.all())

    private val _status = MutableStateFlow<Status>(Status.NotLoaded)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    var loaded: Loaded? = null
        private set

    private val mutex = Mutex()
    private var loadJob: Job? = null

    /** Starts loading if nothing is loaded or loading. Idempotent. */
    fun ensureLoaded() {
        if (loadJob?.isActive == true || loaded != null) return
        loadJob = scope.launch { load() }
    }

    /** Suspends until loading finishes one way or the other. */
    suspend fun awaitLoaded(): Loaded? {
        ensureLoaded()
        status.first { it !is Status.Loading && it !is Status.NotLoaded }
        return loaded
    }

    /** Drops everything and loads again (after a model download or change). */
    fun reload() {
        scope.launch {
            loadJob?.join()
            mutex.withLock {
                loaded?.let { l ->
                    l.llm.unload()
                    l.transcriber.release()
                    l.vad.release()
                }
                loaded = null
                _status.value = Status.NotLoaded
            }
            ensureLoaded()
        }
    }

    private suspend fun load() = mutex.withLock {
        if (loaded != null) return@withLock
        if (catalog.missingForSelection().isNotEmpty()) {
            _status.value = Status.MissingModels
            return@withLock
        }
        _status.value = Status.Loading
        try {
            val t0 = System.nanoTime()
            val llmOption = catalog.selectedLlm
            val refinerOption = catalog.selectedRefiner
            val llm = LlmEngine(
                LlmConfig(
                    catalog.file(llmOption.file),
                    threads = settings.values.value.llmThreads,
                    noThinking = llmOption.noThinking,
                ),
            )
            // ASR/VAD (onnxruntime) and the LLM (llama.cpp) load in parallel.
            val asr = scope.async(Dispatchers.Default) {
                StreamingTranscriber(
                    catalog.streamingModel(catalog.selectedStreaming),
                    refiner = catalog.refinerModel(refinerOption)?.let { OfflineTranscriber(it) },
                )
            }
            val vad = scope.async(Dispatchers.Default) { VadModel(catalog.vad) }
            llm.load()
            val l = Loaded(
                asr.await(), vad.await(), llm,
                llmName = llmOption.label,
                refinerName = refinerOption.label.takeIf { refinerOption != ModelOption.NoRefiner },
            )
            // Evaluate the (constant) system prompt once, so the KV cache
            // already holds it when the first question arrives.
            Agent(llm, registry).warmUp()
            loaded = l
            _status.value = Status.Ready
            Log.i(TAG, "engine ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
        } catch (e: Throwable) {
            Log.e(TAG, "loading models failed", e)
            _status.value = Status.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        const val TAG = "AssistantEngine"
    }
}
