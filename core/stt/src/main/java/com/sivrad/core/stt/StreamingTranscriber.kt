package com.sivrad.core.stt

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

sealed interface TranscriptUpdate {
    val text: String

    /** The hypothesis so far; may still change. */
    data class Partial(override val text: String) : TranscriptUpdate

    /**
     * The audio ended; [text] is the transcript. When a second-pass model
     * re-transcribed the utterance, [streamingText] is what the streaming
     * model had and [refineMillis] how long the second pass took.
     */
    data class Final(
        override val text: String,
        val streamingText: String = text,
        val refineMillis: Long? = null,
    ) : TranscriptUpdate
}

/**
 * Files of a streaming transducer: Zipformer (icefall, Kroko) or NeMo
 * FastConformer. sherpa-onnx tells them apart from the model itself.
 */
data class StreamingAsrModel(
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
)

/**
 * sherpa-onnx's streaming recognizer, loaded once. Each [transcribe] call is
 * one utterance on a fresh stream. With a [refiner], the utterance is
 * transcribed again by an offline model once it ends, and that transcript is
 * the final one: streaming models give the live preview, the offline model
 * the accuracy.
 */
class StreamingTranscriber(
    model: StreamingAsrModel,
    numThreads: Int = 2,
    private val refiner: OfflineTranscriber? = null,
) {
    private val recognizer = OnlineRecognizer(
        assetManager = null,
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = model.encoder.absolutePath,
                    decoder = model.decoder.absolutePath,
                    joiner = model.joiner.absolutePath,
                ),
                tokens = model.tokens.absolutePath,
                numThreads = numThreads,
            ),
            // End of speech is decided by the VAD (core:audio), not by the
            // recognizer's own endpoint rules.
            endpointConfig = EndpointConfig(),
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        ),
    )

    private val decodeThread = Executors.newSingleThreadExecutor { r -> Thread(r, "asr-decode") }
        .asCoroutineDispatcher()

    /**
     * Streams partial transcripts while [audio] (16 kHz mono float frames)
     * flows, then a single [TranscriptUpdate.Final] once it completes.
     */
    fun transcribe(audio: Flow<FloatArray>): Flow<TranscriptUpdate> = flow {
        val stream = recognizer.createStream()
        val utterance = if (refiner != null) SampleBuffer() else null
        try {
            var last = ""
            audio.collect { frame ->
                utterance?.append(frame)
                stream.acceptWaveform(frame, SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = normalize(recognizer.getResult(stream).text)
                if (text != last) {
                    last = text
                    emit(TranscriptUpdate.Partial(text))
                }
            }
            // Flush with a second of silence. Streaming models hold back the
            // last chunk(s) until they see right context; with only 0.3 s the
            // final word or two was routinely dropped (tools/asr-bench: 19.6%
            // → 14.6% WER on the old model, 24.7% → 5.7% on Kroko).
            stream.acceptWaveform(FloatArray(SAMPLE_RATE), SAMPLE_RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val streamed = normalize(recognizer.getResult(stream).text)

            if (refiner != null && utterance != null && utterance.size > SAMPLE_RATE / 4) {
                val t0 = System.nanoTime()
                val refined = normalize(refiner.transcribe(utterance.toArray()))
                val ms = (System.nanoTime() - t0) / 1_000_000
                // An empty second pass (e.g. it heard only noise) should not
                // throw away what the streaming model heard.
                emit(TranscriptUpdate.Final(refined.ifBlank { streamed }, streamed, ms))
            } else {
                emit(TranscriptUpdate.Final(streamed))
            }
        } finally {
            stream.release()
        }
    }.flowOn(decodeThread)

    fun release() {
        recognizer.release()
        refiner?.release()
    }

    private class SampleBuffer {
        private var data = FloatArray(SAMPLE_RATE * 4)
        var size = 0
            private set

        fun append(frame: FloatArray) {
            if (size + frame.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + frame.size))
            frame.copyInto(data, size)
            size += frame.size
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    internal companion object {
        const val SAMPLE_RATE = 16_000

        /**
         * Icefall's English models emit upper case with no punctuation; bring
         * that to sentence case. Anything already cased (Kroko, NeMo,
         * Parakeet, Moonshine) is left alone.
         */
        fun normalize(raw: String): String {
            val t = raw.trim().replace(Regex("\\s+"), " ")
            if (t.any { it.isLowerCase() }) return t
            return t.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
        }
    }
}
