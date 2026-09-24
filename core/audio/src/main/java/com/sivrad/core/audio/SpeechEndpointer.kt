package com.sivrad.core.audio

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

data class EndpointConfig(
    /** Trailing silence after speech that ends the utterance. */
    val endSilenceMs: Int = 900,
    /** Give up if nobody speaks at all for this long. */
    val noSpeechTimeoutMs: Int = 6_000,
    /** Hard cap on one utterance. */
    val maxUtteranceMs: Int = 20_000,
)

enum class EndpointEvent { None, SpeechStarted, EndOfSpeech, NoSpeech, MaxLength }

/** A Silero VAD model loaded once; cheap per-utterance [SpeechEndpointer]s share it. */
class VadModel(file: File) {
    internal val vad = Vad(
        assetManager = null,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = file.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.2f,
                windowSize = AudioCapture.FRAME_SAMPLES,
                maxSpeechDuration = 30f,
            ),
            sampleRate = AudioCapture.SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    fun release() = vad.release()
}

/**
 * Decides when the user has stopped talking, from Silero VAD over the
 * capture frames. Not thread-safe; feed it from the capture flow only.
 */
class SpeechEndpointer(private val model: VadModel, private val config: EndpointConfig = EndpointConfig()) {
    private var elapsedMs = 0L
    private var speechSeen = false
    private var lastSpeechMs = 0L

    init {
        model.vad.reset()
    }

    fun accept(frame: FloatArray): EndpointEvent {
        val vad = model.vad
        vad.acceptWaveform(frame)
        // Completed segments are not used (the ASR has its own copy of the
        // audio); drain them so the VAD's queue does not grow.
        while (!vad.empty()) vad.pop()

        elapsedMs += frame.size * 1000L / AudioCapture.SAMPLE_RATE
        val speaking = vad.isSpeechDetected()
        var event = EndpointEvent.None
        if (speaking) {
            if (!speechSeen) event = EndpointEvent.SpeechStarted
            speechSeen = true
            lastSpeechMs = elapsedMs
        }
        return when {
            event != EndpointEvent.None -> event
            elapsedMs >= config.maxUtteranceMs -> EndpointEvent.MaxLength
            !speechSeen && elapsedMs >= config.noSpeechTimeoutMs -> EndpointEvent.NoSpeech
            speechSeen && elapsedMs - lastSpeechMs >= config.endSilenceMs -> EndpointEvent.EndOfSpeech
            else -> EndpointEvent.None
        }
    }
}
