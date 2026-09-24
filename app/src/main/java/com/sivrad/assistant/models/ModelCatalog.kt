package com.sivrad.assistant.models

import android.content.Context
import com.sivrad.assistant.settings.AppSettings
import com.sivrad.core.stt.ZipformerModel
import java.io.File

/** One file to download. [sha256] blank means "unverified" (user-supplied model). */
data class ModelFile(
    val relativePath: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long?,
)

/**
 * Where every model lives: `models/` under device-protected storage, so they
 * can be read before the first unlock after boot.
 *
 * URLs are pinned to a Hugging Face commit, so a checksum can never go stale
 * because a repository changed under it.
 */
class ModelCatalog(context: Context, private val settings: AppSettings) {
    val root = File(context.createDeviceProtectedStorageContext().filesDir, "models")

    fun file(m: ModelFile) = File(root, m.relativePath)

    val asr = ZipformerModel(
        encoder = File(root, "asr/$ASR_ENCODER"),
        decoder = File(root, "asr/$ASR_DECODER"),
        joiner = File(root, "asr/$ASR_JOINER"),
        tokens = File(root, "asr/tokens.txt"),
    )
    val vad = File(root, "vad/silero_vad.onnx")

    /** The GGUF, named after the configured URL so switching models does not reuse the old file. */
    val llm: File get() = File(root, "llm/" + llmFileName(settings.values.value.llmUrl))

    fun all(): List<ModelFile> {
        val s = settings.values.value
        return listOf(
            ModelFile("asr/$ASR_ENCODER", "$ASR_BASE/$ASR_ENCODER", "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1", 71_083_163),
            ModelFile("asr/$ASR_DECODER", "$ASR_BASE/$ASR_DECODER", "7bf787f90b194b307e5a4ad6a34fadb4e748304c35f78a8d66358a05b13ee6ef", 2_092_621),
            ModelFile("asr/$ASR_JOINER", "$ASR_BASE/$ASR_JOINER", "210591f72b3c56b8364f85f345dca240bc2b4c00632848f4aa923630d5639d3b", 1_026_405),
            ModelFile("asr/tokens.txt", "$ASR_BASE/tokens.txt", "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb", 5_048),
            ModelFile("vad/silero_vad.onnx", VAD_URL, "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28", 1_807_522),
            ModelFile(
                "llm/" + llmFileName(s.llmUrl), s.llmUrl, s.llmSha256,
                if (s.llmUrl == AppSettings.DEFAULT_LLM_URL) 2_497_281_120 else null,
            ),
        )
    }

    fun missing(): List<ModelFile> = all().filterNot { file(it).isFile }

    companion object {
        private const val ASR_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24"
        private const val ASR_ENCODER = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
        private const val ASR_DECODER = "decoder-epoch-99-avg-1-chunk-16-left-128.onnx"
        private const val ASR_JOINER = "joiner-epoch-99-avg-1-chunk-16-left-128.onnx"
        private const val VAD_URL =
            "https://huggingface.co/csukuangfj/vad/resolve/fba88cd2e921609e7675c3aaf51e0b9b295da4bc/silero_vad.onnx"

        fun llmFileName(url: String): String =
            url.substringBefore('?').substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifEmpty { "model.gguf" }
    }
}
