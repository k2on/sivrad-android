package com.sivrad.core.stt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/** A non-streaming model that transcribes a whole utterance at once. */
sealed interface OfflineAsrModel {
    val tokens: File

    /** NVIDIA Parakeet TDT (NeMo transducer). */
    data class NemoTransducer(
        val encoder: File,
        val decoder: File,
        val joiner: File,
        override val tokens: File,
    ) : OfflineAsrModel

    /** Useful Sensors' Moonshine (v1: four models). */
    data class Moonshine(
        val preprocessor: File,
        val encoder: File,
        val uncachedDecoder: File,
        val cachedDecoder: File,
        override val tokens: File,
    ) : OfflineAsrModel
}

/** sherpa-onnx's offline recognizer, loaded once. Not thread-safe; call from one thread. */
class OfflineTranscriber(model: OfflineAsrModel, numThreads: Int = 4) {
    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = StreamingTranscriber.SAMPLE_RATE, featureDim = 80),
            modelConfig = when (model) {
                is OfflineAsrModel.NemoTransducer -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = model.encoder.absolutePath,
                        decoder = model.decoder.absolutePath,
                        joiner = model.joiner.absolutePath,
                    ),
                    modelType = "nemo_transducer",
                    tokens = model.tokens.absolutePath,
                    numThreads = numThreads,
                )
                is OfflineAsrModel.Moonshine -> OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        preprocessor = model.preprocessor.absolutePath,
                        encoder = model.encoder.absolutePath,
                        uncachedDecoder = model.uncachedDecoder.absolutePath,
                        cachedDecoder = model.cachedDecoder.absolutePath,
                    ),
                    tokens = model.tokens.absolutePath,
                    numThreads = numThreads,
                )
            },
            decodingMethod = "greedy_search",
        ),
    )

    /** [samples]: 16 kHz mono in [-1, 1]. */
    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, StreamingTranscriber.SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()
}
