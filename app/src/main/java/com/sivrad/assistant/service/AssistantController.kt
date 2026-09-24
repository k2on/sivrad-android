package com.sivrad.assistant.service

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import com.sivrad.assistant.AssistantEngine
import com.sivrad.assistant.SivradApp
import com.sivrad.core.audio.AudioCapture
import com.sivrad.core.audio.EndpointEvent
import com.sivrad.core.audio.SpeechEndpointer
import com.sivrad.core.llm.Agent
import com.sivrad.core.llm.AgentEvent
import com.sivrad.core.stt.TranscriptUpdate
import com.sivrad.core.tools.Confirmation
import com.sivrad.core.tools.KeyguardGate
import com.sivrad.core.tools.ToolEnvironment
import com.sivrad.core.tools.ToolExecutor
import com.sivrad.core.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { Loading, Listening, Thinking, Unlocking, Confirming, ExecutingTool, Idle, Error }

data class Exchange(val user: String, val reply: String)

data class UiState(
    val phase: Phase = Phase.Loading,
    val status: String = "",
    /** What the user is saying / said this turn. */
    val transcript: String = "",
    /** The reply, streaming. */
    val response: String = "",
    val confirmation: Confirmation? = null,
    val earlier: List<Exchange> = emptyList(),
    val canRetry: Boolean = false,
)

/**
 * One overlay's worth of assistant: listen, transcribe, think, run tools,
 * show the reply. Everything runs in [scope] (the session's main-thread
 * scope); the heavy work hops to the engines' own threads.
 */
class AssistantController(
    private val app: SivradApp,
    private val env: ToolEnvironment,
    private val gate: KeyguardGate,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    private var agent: Agent? = null
    private val manualStop = MutableStateFlow(false)
    private var pendingConfirmation: CompletableDeferred<Boolean>? = null
    private var lastText: String? = null
    private var lastTurnCompleted = false

    val busyUnlocking: Boolean get() = gate.inFlight

    /** Starts a new utterance. Called when the overlay appears and from the Speak button. */
    fun listen() {
        startTurn { capture() }
    }

    /** Sends a typed message instead of a spoken one. */
    fun submit(text: String) {
        val typed = text.trim()
        if (typed.isEmpty()) return
        startTurn {
            _state.update { it.copy(transcript = typed, response = "") }
            if (loadAgent() != null) typed else null
        }
    }

    /** The user switched to the keyboard: stop listening and drop what was heard so far. */
    fun typeInstead() {
        if (_state.value.phase != Phase.Listening && _state.value.phase != Phase.Loading) return
        cancelWork()
        _state.update { it.copy(phase = Phase.Idle, status = "", transcript = "") }
    }

    private fun startTurn(input: suspend () -> String?) {
        cancelWork()
        _state.update {
            // The finished exchange moves up into the history list.
            if (it.transcript.isNotBlank() && it.response.isNotBlank()) {
                it.copy(earlier = it.earlier + Exchange(it.transcript, it.response), transcript = "", response = "")
            } else {
                it
            }
        }
        job = scope.launch {
            try {
                lastText = null
                val text = input() ?: return@launch
                think(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "turn failed", e)
                _state.update {
                    it.copy(phase = Phase.Error, status = e.message ?: "Something went wrong", confirmation = null, canRetry = lastText != null)
                }
            }
        }
    }

    /** Stop button: ends listening early (and keeps what was heard), or stops generating. */
    fun stop() {
        when (_state.value.phase) {
            Phase.Listening -> manualStop.value = true
            Phase.Idle, Phase.Error -> Unit
            else -> {
                cancelWork()
                _state.update { it.copy(phase = Phase.Idle, status = "Stopped", confirmation = null, canRetry = true) }
            }
        }
    }

    /** Retry button: forget the last answer and ask the model again. */
    fun retry() {
        val a = agent ?: return
        cancelWork()
        // A completed turn is in the agent's history and has to come out; an
        // interrupted or failed one already rolled itself back.
        val text = (if (lastTurnCompleted) a.popLastTurn() else lastText) ?: return
        _state.update { it.copy(transcript = text, response = "") }
        job = scope.launch {
            try {
                think(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(phase = Phase.Error, status = e.message ?: "Something went wrong", canRetry = true) }
            }
        }
    }

    fun answerConfirmation(approved: Boolean) {
        pendingConfirmation?.complete(approved)
    }

    /** The overlay went away: stop everything and forget the conversation. */
    fun reset() {
        cancelWork()
        agent?.clear()
        agent = null
        lastText = null
        lastTurnCompleted = false
        _state.value = UiState()
    }

    private fun cancelWork() {
        job?.cancel()
        job = null
        pendingConfirmation?.complete(false)
        pendingConfirmation = null
    }

    /** Listens until end of speech; returns the final transcript, or null if there was none. */
    private suspend fun capture(): String? {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(phase = Phase.Error, status = "Microphone permission missing. Open Sivrad to grant it.") }
            return null
        }
        _state.update { it.copy(transcript = "", response = "") }
        val loaded = loadAgent() ?: return null

        manualStop.value = false
        _state.update { it.copy(phase = Phase.Listening, status = "Listening…") }
        val endpointer = SpeechEndpointer(loaded.vad)
        val audio = AudioCapture.frames().transformWhile { frame ->
            emit(frame)
            val ev = endpointer.accept(frame)
            ev != EndpointEvent.EndOfSpeech && ev != EndpointEvent.NoSpeech &&
                ev != EndpointEvent.MaxLength && !manualStop.value
        }
        var final = ""
        loaded.transcriber.transcribe(audio).collect { u ->
            _state.update { it.copy(transcript = u.text) }
            if (u is TranscriptUpdate.Final) final = u.text
        }
        if (final.isBlank()) {
            _state.update { it.copy(phase = Phase.Idle, status = "Didn't catch that") }
            return null
        }
        return final
    }

    /** Waits for the models; null (with the reason shown) if they are unavailable. */
    private suspend fun loadAgent(): AssistantEngine.Loaded? {
        _state.update { it.copy(phase = Phase.Loading, status = "Loading models…") }
        val loaded = app.engine.awaitLoaded()
        if (loaded == null) {
            val why = when (val s = app.engine.status.value) {
                AssistantEngine.Status.MissingModels -> "Models not downloaded. Open Sivrad to download them."
                is AssistantEngine.Status.Failed -> "Could not load models: ${s.message}"
                else -> "Models unavailable"
            }
            _state.update { it.copy(phase = Phase.Error, status = why) }
            return null
        }
        if (agent == null) agent = Agent(loaded.llm, app.engine.registry)
        return loaded
    }

    private suspend fun think(text: String) {
        val a = agent ?: return
        val executor = ToolExecutor(
            registry = app.engine.registry,
            env = env,
            gate = gate,
            confirm = ::confirm,
            onStage = { stage ->
                _state.update {
                    when (stage) {
                        ToolExecutor.Stage.Unlocking -> it.copy(phase = Phase.Unlocking, status = "Unlock to continue")
                        ToolExecutor.Stage.AwaitingConfirmation -> it.copy(phase = Phase.Confirming, status = "Confirm to continue")
                        ToolExecutor.Stage.Running -> it.copy(phase = Phase.ExecutingTool)
                    }
                }
            },
        )
        lastText = text
        lastTurnCompleted = false
        _state.update { it.copy(transcript = text, response = "", canRetry = false) }
        a.respond(text, executor).collect { ev ->
            _state.update {
                when (ev) {
                    AgentEvent.Thinking -> it.copy(phase = Phase.Thinking, status = "Thinking…")
                    is AgentEvent.Partial -> it.copy(response = ev.text)
                    is AgentEvent.ToolStarted -> it.copy(phase = Phase.ExecutingTool, status = "Running ${ev.name}…")
                    is AgentEvent.ToolFinished -> it.copy(
                        status = when (val r = ev.result) {
                            is ToolResult.Success -> "${ev.name}: done"
                            is ToolResult.Error -> "${ev.name ?: "tool"}: ${r.message.take(80)}"
                        },
                    )
                    is AgentEvent.Reply -> it.also { lastTurnCompleted = true }.copy(
                        phase = Phase.Idle,
                        status = "",
                        response = ev.text,
                        canRetry = true,
                    )
                }
            }
        }
    }

    private suspend fun confirm(c: Confirmation): Boolean {
        val d = CompletableDeferred<Boolean>()
        pendingConfirmation = d
        _state.update { it.copy(confirmation = c) }
        return try {
            d.await()
        } finally {
            pendingConfirmation = null
            _state.update { it.copy(confirmation = null) }
        }
    }

    private companion object {
        const val TAG = "AssistantController"
    }
}
