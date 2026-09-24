package com.sivrad.assistant.models

import android.content.Context
import com.sivrad.assistant.settings.AppSettings
import com.sivrad.core.stt.OfflineAsrModel
import com.sivrad.core.stt.StreamingAsrModel
import java.io.File

/** One file to download. [sha256] blank means "unverified" (user-supplied model). */
data class ModelFile(
    val relativePath: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long?,
)

enum class ModelSlot(val title: String) {
    Streaming("Live speech recognition"),
    Refiner("Final transcript (second pass)"),
    Llm("Language model"),
}

/**
 * One choice for a [ModelSlot]. Several can be downloaded at once and
 * switched between in settings.
 */
sealed interface ModelOption {
    val id: String
    val slot: ModelSlot
    val label: String
    val note: String
    val files: List<ModelFile>
    val totalBytes: Long? get() = files.sumOf { it.sizeBytes ?: return null }

    data class Streaming(
        override val id: String,
        override val label: String,
        override val note: String,
        val dir: String,
        val base: String,
        val encoder: Pair<String, String>,
        val decoder: Pair<String, String>,
        val joiner: Pair<String, String>,
        val tokens: String,
        val sizes: List<Long>,
    ) : ModelOption {
        override val slot = ModelSlot.Streaming
        override val files = listOf(encoder, decoder, joiner, "tokens.txt" to tokens).zip(sizes) { (name, sha), size ->
            ModelFile("$dir/$name", "$base/$name", sha, size)
        }
    }

    sealed interface Refiner : ModelOption {
        override val slot get() = ModelSlot.Refiner
    }

    data object NoRefiner : Refiner {
        override val id = "none"
        override val label = "Off"
        override val note = "Use the live transcript as is. Fastest."
        override val files = emptyList<ModelFile>()
    }

    data class Parakeet(
        override val id: String,
        override val label: String,
        override val note: String,
        override val files: List<ModelFile>,
    ) : Refiner

    data class Moonshine(
        override val id: String,
        override val label: String,
        override val note: String,
        override val files: List<ModelFile>,
    ) : Refiner

    data class Llm(
        override val id: String,
        override val label: String,
        override val note: String,
        val file: ModelFile,
        val noThinking: Boolean,
    ) : ModelOption {
        override val slot = ModelSlot.Llm
        override val files = listOf(file)
    }
}

/**
 * Every model Sivrad knows how to download, and which ones are selected.
 *
 * Files live under `models/` in device-protected storage, so the assistant
 * can read them before the first unlock after boot. URLs are pinned to a
 * Hugging Face commit, so a checksum cannot go stale.
 *
 * The WER figures in the notes come from `tools/asr-bench` (24 synthesised
 * assistant commands, two voices, light noise; ranges over runs). Treat them
 * as a ranking, not as what your voice will get.
 */
class ModelCatalog(context: Context, private val settings: AppSettings) {
    val root = File(context.createDeviceProtectedStorageContext().filesDir, "models")

    fun file(m: ModelFile) = File(root, m.relativePath)

    fun isDownloaded(o: ModelOption) = o.files.all { file(it).isFile }

    fun delete(o: ModelOption) = o.files.forEach { file(it).delete(); File(file(it).path + ".part").delete() }

    val vad = File(root, "vad/silero_vad.onnx")
    private val vadFile = ModelFile(
        "vad/silero_vad.onnx",
        "https://huggingface.co/csukuangfj/vad/resolve/fba88cd2e921609e7675c3aaf51e0b9b295da4bc/silero_vad.onnx",
        "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28", 1_807_522,
    )

    val streamingOptions: List<ModelOption.Streaming> = listOf(
        ModelOption.Streaming(
            id = "kroko-en-2025-08-06",
            label = "Kroko Zipformer (2025)",
            note = "71 MB. Most accurate and fastest streaming model in testing (~6% WER).",
            dir = "asr-kroko-2025-08-06",
            base = "$HF/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/572aaf4e2e0c603c3fc2a574d096e755a178faa1",
            encoder = "encoder.onnx" to "d4881c57449d581e0770fd53fa66c2fdc6cd167d92ece7c715e603defc96d9d4",
            decoder = "decoder.onnx" to "455ba38466fce8d5a57e7db68a323b684079ca4d9e1dd93a740d9b2429aae3b1",
            joiner = "joiner.onnx" to "d406f616736350e2a7df3e39398b78eb2fc1a2ca6973a19d3853fa3227e25b52",
            tokens = "396dbeb5f4858875690716084f54e90d339679d0ba3e6b5b584f3d7589254d2d",
            sizes = listOf(70_092_599, 617_488, 336_817, 6_310),
        ),
        ModelOption.Streaming(
            id = "nemo-fastconformer-80ms",
            label = "NeMo FastConformer 80 ms",
            note = "137 MB. ~8× slower than Kroko and less accurate in testing (14–17% WER).",
            dir = "asr-nemo-fastconformer-80ms",
            base = "$HF/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8/resolve/3fafd319033af1c552e7bc8394f7258afdce91b0",
            encoder = "encoder.int8.onnx" to "8b982c67b45e3b735d3fee49a4fea525b3149b450ba437f4c8a933f4aa6744c0",
            decoder = "decoder.int8.onnx" to "76eec598da07c204747a859a723b99077ef0bbdc19ef4b3f51eb43275662475d",
            joiner = "joiner.int8.onnx" to "67f4291dc170fd06b3695a8511f5199fd5965ee3c77cfbd59afc10e145f173f2",
            tokens = "618dc110fc2213886b52e063ff42329bbdf37a266ca7705184090fa5f39f3131",
            sizes = listOf(131_507_579, 3_955_863, 1_408_182, 11_896),
        ),
        ModelOption.Streaming(
            id = "zipformer-en-2023-06-26",
            label = "Zipformer LibriSpeech (2023)",
            note = "75 MB. The original model, trained on audiobooks only (15–18% WER).",
            dir = "asr",
            base = "$HF/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24",
            encoder = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx" to "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1",
            decoder = "decoder-epoch-99-avg-1-chunk-16-left-128.onnx" to "7bf787f90b194b307e5a4ad6a34fadb4e748304c35f78a8d66358a05b13ee6ef",
            joiner = "joiner-epoch-99-avg-1-chunk-16-left-128.onnx" to "210591f72b3c56b8364f85f345dca240bc2b4c00632848f4aa923630d5639d3b",
            tokens = "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
            sizes = listOf(71_083_163, 2_092_621, 1_026_405, 5_048),
        ),
    )

    val refinerOptions: List<ModelOption.Refiner> = listOf(
        ModelOption.NoRefiner,
        moonshine(
            id = "moonshine-base-en",
            label = "Moonshine base",
            note = "287 MB. Most accurate in testing (1–4% WER). Adds a short pause after you stop talking.",
            repo = "sherpa-onnx-moonshine-base-en-int8/resolve/052b0798ad1bf046a140fdd4efcd9426530fa3f5",
            files = listOf(
                Triple("preprocess.onnx", "ffa630d395c5ccf76f5d4954be5b882df76aaf6491519ec01fd82ea7a3819fb2", 14_077_290L),
                Triple("encode.int8.onnx", "7e38770f776f2e5583a53b052936005df2ba5c833d7e09c2a5fd796b94bf73e2", 50_311_494L),
                Triple("uncached_decode.int8.onnx", "c01f4b35093bcac20d352d23a75a539e772964579f9d024a90e5e6f09cae9987", 122_120_451L),
                Triple("cached_decode.int8.onnx", "2db74e51cedf64a8b1be3c8192e0bb5e4923af0e90bd9e87f8e8771873f8ea03", 99_983_837L),
                Triple("tokens.txt", MOONSHINE_TOKENS, 436_688L),
            ),
        ),
        moonshine(
            id = "moonshine-tiny-en",
            label = "Moonshine tiny",
            note = "124 MB. Faster second pass, a little less accurate (6–7% WER).",
            repo = "sherpa-onnx-moonshine-tiny-en-int8/resolve/bf2b762c076d8ea61e2af0b3851c9564fb77552e",
            files = listOf(
                Triple("preprocess.onnx", "f33addce61a143460fe753b5ee5b7db255e5140b5b779c065b94f6c83ff0bf4e", 6_800_738L),
                Triple("encode.int8.onnx", "8774dfba578de027ec6595c2c654a0836434489bc963a0db124a7f181f571acb", 18_249_187L),
                Triple("uncached_decode.int8.onnx", "216737000dd5881a17aa043f6bbd286add33e4c3b0ae257153e2ec15438bdc41", 53_216_096L),
                Triple("cached_decode.int8.onnx", "2aff28bba6a03d8dcf5c9feac45462629bae37317442299f28115ad09da773f6", 45_264_830L),
                Triple("tokens.txt", MOONSHINE_TOKENS, 436_688L),
            ),
        ),
        ModelOption.Parakeet(
            id = "parakeet-tdt-0.6b-v2",
            label = "Parakeet TDT 0.6B v2",
            note = "661 MB. NVIDIA's model, trained on far more real speech than the test voices (7% WER). Slowest.",
            files = listOf(
                Triple("encoder.int8.onnx", "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab", 652_184_296L),
                Triple("decoder.int8.onnx", "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e", 7_257_753L),
                Triple("joiner.int8.onnx", "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2", 1_739_080L),
                Triple("tokens.txt", "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d", 9_384L),
            ).map { (name, sha, size) ->
                ModelFile(
                    "refine-parakeet-tdt-0.6b-v2/$name",
                    "$HF/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/1ab9323565ddb038682214b292f588070a538ce2/$name",
                    sha, size,
                )
            },
        ),
    )

    val llmOptions: List<ModelOption.Llm> = listOf(
        llm(
            "qwen3-4b-2507-q4_0", "Qwen3 4B Instruct 2507 · Q4_0",
            "2.4 GB. Default. Q4_0 runs on llama.cpp's fastest Arm kernels (KleidiAI + i8mm).",
            "unsloth/Qwen3-4B-Instruct-2507-GGUF", "a06e946bb6b655725eafa393f4a9745d460374c9",
            "Qwen3-4B-Instruct-2507-Q4_0.gguf", "e0ba675d86ab277c61701c6793659b2ae801d95e3be791464c321e6fbf613be2",
            2_375_773_280, noThinking = false,
        ),
        llm(
            "qwen3-4b-2507-q4_k_m", "Qwen3 4B Instruct 2507 · Q4_K_M",
            "2.5 GB. The original download. Slightly better quality, slower than Q4_0 on Arm.",
            "unsloth/Qwen3-4B-Instruct-2507-GGUF", "a06e946bb6b655725eafa393f4a9745d460374c9",
            "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597",
            2_497_281_120, noThinking = false, dir = "llm",
        ),
        llm(
            "qwen3-1.7b-q4_0", "Qwen3 1.7B · Q4_0",
            "1.1 GB. About 2–2.5× faster than 4B. Weaker: in testing it set 30 s for \"five minutes\".",
            "unsloth/Qwen3-1.7B-GGUF", "d7f544eead698dbd1f15126ef60b45a1e1933222",
            "Qwen3-1.7B-Q4_0.gguf", "c876f159707a4e4f70e045106c69db15bfc935a4981706fd4f65c6e7ea1e81c5",
            1_056_782_912, noThinking = true,
        ),
        llm(
            "qwen3-0.6b-q4_0", "Qwen3 0.6B · Q4_0",
            "382 MB. Very fast; expect mistakes. Mostly for comparing speed.",
            "unsloth/Qwen3-0.6B-GGUF", "50968a4468ef4233ed78cd7c3de230dd1d61a56b",
            "Qwen3-0.6B-Q4_0.gguf", "33bcc57074ec7b6eada5a90651ee546ec0c2b271002c22baf9f1b2dd1e8f75cb",
            382_156_480, noThinking = true,
        ),
    )

    /** The model at the URL in settings, if one is set. */
    val customLlm: ModelOption.Llm?
        get() {
            val s = settings.values.value
            if (s.customLlmUrl.isBlank()) return null
            return ModelOption.Llm(
                id = CUSTOM_LLM, label = "Custom: ${llmFileName(s.customLlmUrl)}", note = s.customLlmUrl,
                file = ModelFile("llm-custom/" + llmFileName(s.customLlmUrl), s.customLlmUrl, s.customLlmSha256, null),
                noThinking = s.customLlmNoThinking,
            )
        }

    fun options(slot: ModelSlot): List<ModelOption> = when (slot) {
        ModelSlot.Streaming -> streamingOptions
        ModelSlot.Refiner -> refinerOptions
        ModelSlot.Llm -> llmOptions + listOfNotNull(customLlm)
    }

    fun selected(slot: ModelSlot): ModelOption {
        val s = settings.values.value
        val id = when (slot) {
            ModelSlot.Streaming -> s.streamingModel
            ModelSlot.Refiner -> s.refinerModel
            ModelSlot.Llm -> s.llmModel
        }
        val all = options(slot)
        return all.firstOrNull { it.id == id } ?: all.first { it.id == AppSettings.defaultFor(slot) }
    }

    val selectedStreaming get() = selected(ModelSlot.Streaming) as ModelOption.Streaming
    val selectedRefiner get() = selected(ModelSlot.Refiner) as ModelOption.Refiner
    val selectedLlm get() = selected(ModelSlot.Llm) as ModelOption.Llm

    fun streamingModel(o: ModelOption.Streaming) = StreamingAsrModel(
        encoder = File(root, "${o.dir}/${o.encoder.first}"),
        decoder = File(root, "${o.dir}/${o.decoder.first}"),
        joiner = File(root, "${o.dir}/${o.joiner.first}"),
        tokens = File(root, "${o.dir}/tokens.txt"),
    )

    fun refinerModel(o: ModelOption.Refiner): OfflineAsrModel? {
        fun f(i: Int) = file(o.files[i])
        return when (o) {
            ModelOption.NoRefiner -> null
            is ModelOption.Moonshine -> OfflineAsrModel.Moonshine(f(0), f(1), f(2), f(3), f(4))
            is ModelOption.Parakeet -> OfflineAsrModel.NemoTransducer(f(0), f(1), f(2), f(3))
        }
    }

    /** Files the current selection needs that are not on disk. */
    fun missingForSelection(): List<ModelFile> =
        (listOf(vadFile) + selectedStreaming.files + selectedRefiner.files + selectedLlm.files)
            .filterNot { file(it).isFile }

    private fun moonshine(
        id: String, label: String, note: String, repo: String, files: List<Triple<String, String, Long>>,
    ) = ModelOption.Moonshine(id, label, note, files.map { (name, sha, size) ->
        ModelFile("refine-$id/$name", "$HF/$repo/$name", sha, size)
    })

    private fun llm(
        id: String, label: String, note: String, repo: String, rev: String, name: String, sha: String,
        size: Long, noThinking: Boolean, dir: String = "llm-$id",
    ) = ModelOption.Llm(
        id, label, note,
        ModelFile("$dir/$name", "https://huggingface.co/$repo/resolve/$rev/$name", sha, size),
        noThinking,
    )

    companion object {
        const val CUSTOM_LLM = "custom"
        private const val HF = "https://huggingface.co/csukuangfj"
        private const val MOONSHINE_TOKENS = "1165c2aeb9f72f457a83be2d459a09054f27490acd9b41bd43794dfd25e296ea"

        fun llmFileName(url: String): String =
            url.substringBefore('?').substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifEmpty { "model.gguf" }
    }
}
