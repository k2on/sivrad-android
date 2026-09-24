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

    /** The audio ended; this is the transcript. */
    data class Final(override val text: String) : TranscriptUpdate
}

/** Files of a streaming Zipformer transducer. */
data class ZipformerModel(
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
) {
    val files get() = listOf(encoder, decoder, joiner, tokens)
}

/**
 * sherpa-onnx's streaming recognizer, loaded once. Each [transcribe] call is
 * one utterance on a fresh stream.
 */
class StreamingTranscriber(model: ZipformerModel, numThreads: Int = 2) {
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
                modelType = "zipformer2",
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
        try {
            var last = ""
            audio.collect { frame ->
                stream.acceptWaveform(frame, SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = normalize(recognizer.getResult(stream).text)
                if (text != last) {
                    last = text
                    emit(TranscriptUpdate.Partial(text))
                }
            }
            // Flush: a little silence so the last chunk is decoded, then end.
            stream.acceptWaveform(FloatArray(SAMPLE_RATE * 3 / 10), SAMPLE_RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            emit(TranscriptUpdate.Final(normalize(recognizer.getResult(stream).text)))
        } finally {
            stream.release()
        }
    }.flowOn(decodeThread)

    fun release() = recognizer.release()

    private companion object {
        const val SAMPLE_RATE = 16_000

        /** The English Zipformer emits upper case with no punctuation. */
        fun normalize(raw: String): String {
            val t = raw.trim().lowercase(Locale.US)
            return t.replaceFirstChar { it.titlecase(Locale.US) }
        }
    }
}
