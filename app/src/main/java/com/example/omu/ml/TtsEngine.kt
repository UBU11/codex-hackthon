package com.example.omu.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Random
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt


class TtsEngine(
    context: Context,
    private val voiceName: String = DEFAULT_VOICE_NAME
) {
    private val appContext = context.applicationContext
    private val assetManager = appContext.assets
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val mutex = Mutex()
    private val voiceProfileParser = VoiceProfileParser(appContext)

    private var config: SuperTonicConfig? = null
    private var textProcessor: UnicodeProcessor? = null
    private var defaultVoiceProfile: VoiceProfile? = null
    private var durationSession: OrtSession? = null
    private var textEncoderSession: OrtSession? = null
    private var vectorSession: OrtSession? = null
    private var vocoderSession: OrtSession? = null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (durationSession != null) return@withLock

            initializeFromFirstAvailableLayout()
        }
    }

    suspend fun synthesize(text: String): FloatArray {
        return synthesize(text, ensureDefaultVoiceProfile())
    }

    suspend fun synthesize(
        text: String,
        voiceStyleArray: FloatArray
    ): FloatArray {
        val template = ensureDefaultVoiceProfile()
        return synthesize(text, voiceProfileFromEmbedding(voiceStyleArray, template))
    }

    suspend fun synthesize(
        text: String,
        voiceProfile: VoiceProfile
    ): FloatArray = withContext(Dispatchers.Default) {
        val spokenText = text.replace(Regex("\\s+"), " ").trim()
        if (spokenText.isBlank()) return@withContext FloatArray(0)

        initialize()
        mutex.withLock {
            synthesizeInternal(spokenText, voiceProfile)
        }
    }

    fun close() {
        closeModelResources()
    }

    private suspend fun initializeFromFirstAvailableLayout() {
        val failures = ArrayList<String>()

        for (modelLayout in resolveModelLayouts()) {
            try {
                val options = createSessionOptions()
                try {
                    config = loadConfig(modelLayout.ttsConfigFile)
                    textProcessor = UnicodeProcessor(modelLayout.unicodeIndexerFile)
                    defaultVoiceProfile = voiceProfileParser.loadVoiceProfile(
                        modelLayout.voiceStyleFile.absolutePath
                    )
                    durationSession = env.createSession(
                        modelLayout.durationPredictorFile.absolutePath,
                        options
                    )
                    textEncoderSession = env.createSession(
                        modelLayout.textEncoderFile.absolutePath,
                        options
                    )
                    vectorSession = env.createSession(
                        modelLayout.vectorEstimatorFile.absolutePath,
                        options
                    )
                    vocoderSession = env.createSession(
                        modelLayout.vocoderFile.absolutePath,
                        options
                    )
                } finally {
                    runCatching { options.close() }
                }
                Log.i(TAG, "Initialized SuperTonic 3 from ${modelLayout.modelDir.absolutePath}")
                return
            } catch (error: Throwable) {
                closeModelResources()
                failures += "${modelLayout.modelDir.absolutePath}: ${error.message}"
                Log.w(
                    TAG,
                    "SuperTonic 3 layout failed at ${modelLayout.modelDir.absolutePath}",
                    error
                )
            }
        }

        throw IllegalStateException(
            "SuperTonic 3 files could not be loaded. Tried: ${failures.joinToString()}. " +
                "Supported layouts: android_tts_assets/onnx, " +
                "android_tts_assets/$SUPERTONIC_MODEL_DIR/onnx, and $SUPERTONIC_MODEL_DIR/onnx."
        )
    }

    private fun closeModelResources() {
        defaultVoiceProfile = null
        runCatching { durationSession?.close() }
        runCatching { textEncoderSession?.close() }
        runCatching { vectorSession?.close() }
        runCatching { vocoderSession?.close() }
        durationSession = null
        textEncoderSession = null
        vectorSession = null
        vocoderSession = null
    }

    private suspend fun ensureDefaultVoiceProfile(): VoiceProfile {
        initialize()
        return requireNotNull(defaultVoiceProfile) {
            "Default SuperTonic voice profile is unavailable"
        }
    }

    private fun synthesizeInternal(
        text: String,
        voiceProfile: VoiceProfile
    ): FloatArray {
        val chunks = chunkText(text, maxChunkLength = MAX_CHUNK_LENGTH)
        val pieces = ArrayList<FloatArray>()
        var totalSamples = 0
        val styleTtlTensor = createFloatTensor(
            voiceProfile.styleTtl.data,
            voiceProfile.styleTtl.shape
        )
        val styleDpTensor = createFloatTensor(
            voiceProfile.styleDp.data,
            voiceProfile.styleDp.shape
        )

        try {
            chunks.forEachIndexed { index, chunk ->
                val result = infer(
                    textList = listOf(chunk),
                    langList = listOf(DEFAULT_LANGUAGE),
                    styleTtlTensor = styleTtlTensor,
                    styleDpTensor = styleDpTensor
                )
                val expectedSamples = (sampleRate() * result.duration.firstOrNull().orZero())
                    .roundToInt()
                    .coerceIn(0, result.wav.size)
                val wavChunk = result.wav.copyOf(expectedSamples)

                if (index > 0) {
                    val silence = FloatArray((SILENCE_DURATION_SECONDS * sampleRate()).roundToInt())
                    pieces.add(silence)
                    totalSamples += silence.size
                }

                pieces.add(wavChunk)
                totalSamples += wavChunk.size
            }
        } finally {
            styleTtlTensor.close()
            styleDpTensor.close()
        }

        val combined = FloatArray(totalSamples)
        var offset = 0
        for (piece in pieces) {
            piece.copyInto(combined, offset)
            offset += piece.size
        }
        return combined
    }

    private fun infer(
        textList: List<String>,
        langList: List<String>,
        styleTtlTensor: OnnxTensor,
        styleDpTensor: OnnxTensor
    ): TtsResult {
        val activeConfig = requireNotNull(config)
        val activeProcessor = requireNotNull(textProcessor)
        val activeDurationSession = requireNotNull(durationSession)
        val activeTextEncoderSession = requireNotNull(textEncoderSession)
        val activeVectorSession = requireNotNull(vectorSession)
        val activeVocoderSession = requireNotNull(vocoderSession)

        val batchSize = textList.size
        val textProcessResult = activeProcessor.process(textList, langList)
        val textIdsTensor = createLongTensor(textProcessResult.textIds)
        val textMaskTensor = createFloatTensor(textProcessResult.textMask)

        try {
            val duration = activeDurationSession.run(
                mapOf(
                    "text_ids" to textIdsTensor,
                    "style_dp" to styleDpTensor,
                    "text_mask" to textMaskTensor
                )
            ).use { result ->
                firstFloatArray(result[0].value).map { it / DEFAULT_SPEED }.toFloatArray()
            }

            val textEncoderResult = activeTextEncoderSession.run(
                mapOf(
                    "text_ids" to textIdsTensor,
                    "style_ttl" to styleTtlTensor,
                    "text_mask" to textMaskTensor
                )
            )

            try {
                val textEmbTensor = textEncoderResult[0] as OnnxTensor
                val noisyLatentResult = sampleNoisyLatent(duration, activeConfig)
                var latent = noisyLatentResult.noisyLatent
                val latentMask = noisyLatentResult.latentMask
                val latentMaskTensor = createFloatTensor(latentMask)
                val totalStepTensor = createFloatTensor(
                    FloatArray(batchSize) { DEFAULT_TOTAL_STEPS.toFloat() },
                    longArrayOf(batchSize.toLong())
                )

                try {
                    for (step in 0 until DEFAULT_TOTAL_STEPS) {
                        val currentStepTensor = createFloatTensor(
                            FloatArray(batchSize) { step.toFloat() },
                            longArrayOf(batchSize.toLong())
                        )
                        val noisyLatentTensor = createFloatTensor(latent)

                        try {
                            activeVectorSession.run(
                                mapOf(
                                    "noisy_latent" to noisyLatentTensor,
                                    "text_emb" to textEmbTensor,
                                    "style_ttl" to styleTtlTensor,
                                    "latent_mask" to latentMaskTensor,
                                    "text_mask" to textMaskTensor,
                                    "current_step" to currentStepTensor,
                                    "total_step" to totalStepTensor
                                )
                            ).use { vectorResult ->
                                latent = asFloat3d(vectorResult[0].value)
                            }
                        } finally {
                            currentStepTensor.close()
                            noisyLatentTensor.close()
                        }
                    }
                } finally {
                    latentMaskTensor.close()
                    totalStepTensor.close()
                }

                val finalLatentTensor = createFloatTensor(latent)
                try {
                    val wav = activeVocoderSession.run(
                        mapOf("latent" to finalLatentTensor)
                    ).use { vocoderResult ->
                        flattenWavBatch(asFloat2d(vocoderResult[0].value))
                    }
                    return TtsResult(wav, duration)
                } finally {
                    finalLatentTensor.close()
                }
            } finally {
                textEncoderResult.close()
            }
        } finally {
            textIdsTensor.close()
            textMaskTensor.close()
        }
    }

    private fun resolveModelLayouts(): List<SuperTonicModelLayout> {
        val externalFilesDir = appContext.getExternalFilesDir(null)
        val gemmaParentDir = GemmaEngine.getGemmaModelFile(appContext)?.parentFile
        val packageFilesDir = "Android/data/${appContext.packageName}/files"
        val rootCandidates = linkedSetOf<File>()

        listOfNotNull(
            File("/sdcard", packageFilesDir),
            File("/storage/emulated/0", packageFilesDir),
            gemmaParentDir,
            externalFilesDir,
            appContext.filesDir
        ).forEach { root ->
            rootCandidates.add(root)
        }

        val modelDirCandidates = linkedSetOf<File>()
        val layouts = ArrayList<SuperTonicModelLayout>()
        layouts += resolveAssetModelLayouts()

        rootCandidates.forEach { root ->
            val androidTtsAssetsDir = File(root, ANDROID_TTS_ASSETS_DIR)
            modelDirCandidates.add(androidTtsAssetsDir)
            SUPERTONIC_MODEL_DIR_NAMES.forEach { name ->
                modelDirCandidates.add(File(androidTtsAssetsDir, name))
            }

            SUPERTONIC_MODEL_DIR_NAMES.forEach { name ->
                modelDirCandidates.add(File(root, name))
            }
            modelDirCandidates.add(root)
        }

        layouts += modelDirCandidates.map { modelDir -> modelDir.toLayout() }
        return layouts
    }

    private fun resolveAssetModelLayouts(): List<SuperTonicModelLayout> {
        val candidates = linkedSetOf<String>()
        candidates.add(ANDROID_TTS_ASSETS_DIR)
        candidates.add("$ANDROID_TTS_ASSETS_DIR/$SUPERTONIC_MODEL_DIR")
        candidates.add(SUPERTONIC_MODEL_DIR)

        runCatching {
            assetManager.list("")?.forEach { root ->
                candidates.add(root)
                candidates.add("$root/$ANDROID_TTS_ASSETS_DIR")
                candidates.add("$root/$SUPERTONIC_MODEL_DIR")
            }
        }

        return candidates.mapNotNull { assetModelDir ->
            stageAssetLayout(assetModelDir.trim('/'))
        }
    }

    private fun stageAssetLayout(assetModelDir: String): SuperTonicModelLayout? {
        if (assetModelDir.isBlank()) return null

        val requiredAssets = listOf(
            "$assetModelDir/onnx/tts.json",
            "$assetModelDir/onnx/unicode_indexer.json",
            "$assetModelDir/onnx/duration_predictor.onnx",
            "$assetModelDir/onnx/text_encoder.onnx",
            "$assetModelDir/onnx/vector_estimator.onnx",
            "$assetModelDir/onnx/vocoder.onnx",
            "$assetModelDir/voice_styles/$voiceName.json"
        )
        if (!requiredAssets.all(::assetCanOpen)) return null

        val targetDir = File(
            appContext.filesDir,
            "$ASSET_TTS_CACHE_DIR/${assetModelDir.replace('/', '_')}"
        )

        return runCatching {
            requiredAssets.forEach { assetPath ->
                val relativePath = assetPath.removePrefix("$assetModelDir/")
                copyAssetIfNeeded(assetPath, File(targetDir, relativePath))
            }
            Log.i(TAG, "Staged SuperTonic 3 assets from $assetModelDir to ${targetDir.absolutePath}")
            targetDir.toLayout()
        }.onFailure { error ->
            Log.w(TAG, "Failed to stage SuperTonic 3 assets from $assetModelDir", error)
        }.getOrNull()
    }

    private fun assetCanOpen(assetPath: String): Boolean {
        return runCatching {
            assetManager.open(assetPath).use { stream ->
                stream.read() >= 0
            }
        }.getOrDefault(false)
    }

    private fun copyAssetIfNeeded(assetPath: String, targetFile: File) {
        if (targetFile.length() > 0L) return

        targetFile.parentFile?.mkdirs()
        assetManager.open(assetPath).use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun File.toLayout(): SuperTonicModelLayout {
        val onnxDir = File(this, "onnx")
        return SuperTonicModelLayout(
            modelDir = this,
            ttsConfigFile = File(onnxDir, "tts.json"),
            unicodeIndexerFile = File(onnxDir, "unicode_indexer.json"),
            durationPredictorFile = File(onnxDir, "duration_predictor.onnx"),
            textEncoderFile = File(onnxDir, "text_encoder.onnx"),
            vectorEstimatorFile = File(onnxDir, "vector_estimator.onnx"),
            vocoderFile = File(onnxDir, "vocoder.onnx"),
            voiceStyleFile = File(this, "voice_styles/$voiceName.json")
        )
    }

    private fun File.toValidLayout(): SuperTonicModelLayout? {
        val layout = toLayout()
        return layout.takeIf { it.hasRequiredFiles() }
    }

    private fun sampleNoisyLatent(
        duration: FloatArray,
        activeConfig: SuperTonicConfig
    ): NoisyLatentResult {
        val batchSize = duration.size
        val sampleRate = activeConfig.sampleRate
        val maxDuration = duration.maxOrNull()?.coerceAtLeast(0f) ?: 0f
        val wavLenMax = (maxDuration * sampleRate).roundToInt().coerceAtLeast(1)
        val wavLengths = IntArray(batchSize) { index ->
            (duration[index].coerceAtLeast(0f) * sampleRate).roundToInt().coerceAtLeast(1)
        }

        val chunkSize = activeConfig.baseChunkSize * activeConfig.chunkCompressFactor
        val latentLen = ((wavLenMax + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        val latentDim = activeConfig.latentDim * activeConfig.chunkCompressFactor
        val random = Random()
        val noisyLatent = Array(batchSize) {
            Array(latentDim) {
                FloatArray(latentLen)
            }
        }

        for (batch in 0 until batchSize) {
            for (dim in 0 until latentDim) {
                for (time in 0 until latentLen) {
                    noisyLatent[batch][dim][time] = random.nextGaussianFloat()
                }
            }
        }

        val latentMask = getLatentMask(wavLengths, activeConfig)
        for (batch in 0 until batchSize) {
            for (dim in 0 until latentDim) {
                for (time in 0 until latentLen) {
                    noisyLatent[batch][dim][time] *= latentMask[batch][0][time]
                }
            }
        }

        return NoisyLatentResult(noisyLatent, latentMask)
    }

    private fun getLatentMask(
        wavLengths: IntArray,
        activeConfig: SuperTonicConfig
    ): Array<Array<FloatArray>> {
        val latentSize = activeConfig.baseChunkSize * activeConfig.chunkCompressFactor
        val latentLengths = IntArray(wavLengths.size) { index ->
            (wavLengths[index] + latentSize - 1) / latentSize
        }
        val maxLen = latentLengths.maxOrNull()?.coerceAtLeast(1) ?: 1
        return Array(wavLengths.size) { batch ->
            Array(1) {
                FloatArray(maxLen) { time ->
                    if (time < latentLengths[batch]) 1f else 0f
                }
            }
        }
    }

    private fun loadConfig(file: File): SuperTonicConfig {
        val root = JSONObject(file.readText())
        val ae = root.getJSONObject("ae")
        val ttl = root.getJSONObject("ttl")
        return SuperTonicConfig(
            sampleRate = ae.getInt("sample_rate"),
            baseChunkSize = ae.getInt("base_chunk_size"),
            chunkCompressFactor = ttl.getInt("chunk_compress_factor"),
            latentDim = ttl.getInt("latent_dim")
        )
    }

    private fun createSessionOptions(): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(SUPERTONIC_THREADS)
            runCatching {
                addXnnpack(mapOf("intra_op_num_threads" to SUPERTONIC_THREADS.toString()))
            }.onFailure { error ->
                Log.w(TAG, "XNNPACK unavailable for SuperTonic 3; using default ONNX Runtime CPU", error)
            }
        }
    }

    private fun createFloatTensor(array: Array<Array<FloatArray>>): OnnxTensor {
        val dim0 = array.size
        val dim1 = array.firstOrNull()?.size ?: 0
        val dim2 = array.firstOrNull()?.firstOrNull()?.size ?: 0
        val flat = FloatArray(dim0 * dim1 * dim2)
        var index = 0

        for (i in 0 until dim0) {
            for (j in 0 until dim1) {
                for (k in 0 until dim2) {
                    flat[index++] = array[i][j][k]
                }
            }
        }

        return createFloatTensor(
            flat,
            longArrayOf(dim0.toLong(), dim1.toLong(), dim2.toLong())
        )
    }

    private fun createFloatTensor(values: FloatArray, shape: LongArray): OnnxTensor {
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape)
    }

    private fun createLongTensor(values: Array<LongArray>): OnnxTensor {
        val dim0 = values.size
        val dim1 = values.firstOrNull()?.size ?: 0
        val flat = LongArray(dim0 * dim1)
        var index = 0

        for (i in 0 until dim0) {
            for (j in 0 until dim1) {
                flat[index++] = values[i][j]
            }
        }

        return OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(flat),
            longArrayOf(dim0.toLong(), dim1.toLong())
        )
    }

    private fun firstFloatArray(value: Any?): FloatArray {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> firstFloatArray(value.firstOrNull())
            else -> FloatArray(0)
        }
    }

    private fun asFloat2d(value: Any?): Array<FloatArray> {
        return when (value) {
            is FloatArray -> arrayOf(value)
            is Array<*> -> Array(value.size) { index ->
                value[index] as? FloatArray
                    ?: throw IllegalStateException("Expected float array at index $index")
            }
            else -> throw IllegalStateException("Expected 2D float tensor output")
        }
    }

    private fun asFloat3d(value: Any?): Array<Array<FloatArray>> {
        return when (value) {
            is Array<*> -> Array(value.size) { index -> asFloat2d(value[index]) }
            else -> throw IllegalStateException("Expected 3D float tensor output")
        }
    }

    private fun flattenWavBatch(batch: Array<FloatArray>): FloatArray {
        val totalSamples = batch.sumOf { it.size }
        val wav = FloatArray(totalSamples)
        var offset = 0
        for (item in batch) {
            item.copyInto(wav, offset)
            offset += item.size
        }
        return wav
    }

    private fun chunkText(text: String, maxChunkLength: Int): List<String> {
        val normalized = text.trim()
        if (normalized.length <= maxChunkLength) return listOf(normalized)

        val chunks = ArrayList<String>()
        val sentences = normalized.split(Regex("(?<=[.!?])\\s+"))
        val current = StringBuilder()

        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + sentence.length + 1 > maxChunkLength) {
                chunks.add(current.toString())
                current.clear()
            }
            if (sentence.length > maxChunkLength) {
                sentence.split(Regex("\\s+")).forEach { word ->
                    if (current.isNotEmpty() && current.length + word.length + 1 > maxChunkLength) {
                        chunks.add(current.toString())
                        current.clear()
                    }
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(word)
                }
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }

        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks.ifEmpty { listOf(normalized.take(maxChunkLength)) }
    }

    private fun sampleRate(): Int = requireNotNull(config).sampleRate

    private fun voiceProfileFromEmbedding(
        embedding: FloatArray,
        template: VoiceProfile
    ): VoiceProfile {
        return VoiceProfileParser.createVoiceProfileFromEmbedding(
            id = "runtime_embedding",
            label = "Runtime voice",
            embedding = embedding,
            template = template
        )
    }

    private fun Random.nextGaussianFloat(): Float {
        val u1 = nextDouble().coerceAtLeast(1e-10)
        val u2 = nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)).toFloat()
    }

    private fun Float?.orZero(): Float = this ?: 0f

    private class UnicodeProcessor(indexerFile: File) {
        private val indexer: LongArray = loadIndexer(indexerFile)

        fun process(textList: List<String>, langList: List<String>): TextProcessResult {
            val processedTexts = textList.mapIndexed { index, text ->
                preprocessText(text, langList[index])
            }
            val unicodeValues = processedTexts.map { text -> text.codePoints().toArray() }
            val maxLen = unicodeValues.maxOfOrNull { it.size } ?: 1
            val textIds = Array(unicodeValues.size) { LongArray(maxLen) }
            val textMask = Array(unicodeValues.size) {
                Array(1) {
                    FloatArray(maxLen)
                }
            }

            unicodeValues.forEachIndexed { row, values ->
                values.forEachIndexed { col, codePoint ->
                    textIds[row][col] = if (codePoint in indexer.indices) {
                        indexer[codePoint]
                    } else {
                        UNKNOWN_TEXT_ID
                    }
                    textMask[row][0][col] = 1f
                }
            }

            return TextProcessResult(textIds, textMask)
        }

        private fun preprocessText(rawText: String, lang: String): String {
            require(lang in AVAILABLE_LANGUAGES) { "Invalid SuperTonic language: $lang" }
            var text = Normalizer.normalize(rawText, Normalizer.Form.NFKD)
            text = removeEmojis(text)
            text = text
                .replace('\u2013', '-')
                .replace('\u2011', '-')
                .replace('\u2014', '-')
                .replace('_', ' ')
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u00B4', '\'')
                .replace('`', '\'')
                .replace('[', ' ')
                .replace(']', ' ')
                .replace('|', ' ')
                .replace('/', ' ')
                .replace('#', ' ')
                .replace('<', ' ')
                .replace('>', ' ')
            text = text.replace(Regex("[\\\\]"), "")
            text = text.replace("@", " at ")
            text = text.replace("e.g.,", "for example, ")
            text = text.replace("i.e.,", "that is, ")
            text = text.replace(Regex("\\s+"), " ").trim()
            if (text.isBlank()) text = "."
            if (!text.matches(Regex(".*[.!?;:,'\"\\)\\]}]$"))) {
                text += "."
            }
            return "<$lang>$text</$lang>"
        }

        private fun removeEmojis(text: String): String {
            val result = StringBuilder()
            val iterator = text.codePoints().iterator()
            while (iterator.hasNext()) {
                val codePoint = iterator.nextInt()
                if (!isEmoji(codePoint)) {
                    result.appendCodePoint(codePoint)
                }
            }
            return result.toString()
        }

        private fun isEmoji(codePoint: Int): Boolean {
            return codePoint in 0x1F600..0x1F64F ||
                codePoint in 0x1F300..0x1F5FF ||
                codePoint in 0x1F680..0x1F6FF ||
                codePoint in 0x1F700..0x1F77F ||
                codePoint in 0x1F780..0x1F7FF ||
                codePoint in 0x1F800..0x1F8FF ||
                codePoint in 0x1F900..0x1F9FF ||
                codePoint in 0x1FA00..0x1FA6F ||
                codePoint in 0x1FA70..0x1FAFF ||
                codePoint in 0x2600..0x26FF ||
                codePoint in 0x2700..0x27BF ||
                codePoint in 0x1F1E6..0x1F1FF
        }

        companion object {
            private val AVAILABLE_LANGUAGES = setOf(
                "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es",
                "et", "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv",
                "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk",
                "vi", "na"
            )
            private const val UNKNOWN_TEXT_ID = 0L

            private fun loadIndexer(indexerFile: File): LongArray {
                val array = JSONArray(indexerFile.readText())
                return LongArray(array.length()) { index -> array.getLong(index) }
            }
        }
    }

    private data class SuperTonicConfig(
        val sampleRate: Int,
        val baseChunkSize: Int,
        val chunkCompressFactor: Int,
        val latentDim: Int
    )

    private data class SuperTonicModelLayout(
        val modelDir: File,
        val ttsConfigFile: File,
        val unicodeIndexerFile: File,
        val durationPredictorFile: File,
        val textEncoderFile: File,
        val vectorEstimatorFile: File,
        val vocoderFile: File,
        val voiceStyleFile: File
    ) {
        fun hasRequiredFiles(): Boolean {
            return listOf(
                ttsConfigFile,
                unicodeIndexerFile,
                durationPredictorFile,
                textEncoderFile,
                vectorEstimatorFile,
                vocoderFile,
                voiceStyleFile
            ).all { file -> file.canOpenForRead() }
        }

        private fun File.canOpenForRead(): Boolean {
            return runCatching {
                inputStream().use { stream ->
                    stream.read() >= 0
                }
            }.getOrDefault(false)
        }
    }

    private data class TextProcessResult(
        val textIds: Array<LongArray>,
        val textMask: Array<Array<FloatArray>>
    )

    private data class TtsResult(
        val wav: FloatArray,
        val duration: FloatArray
    )

    private data class NoisyLatentResult(
        val noisyLatent: Array<Array<FloatArray>>,
        val latentMask: Array<Array<FloatArray>>
    )

    companion object {
        private const val TAG = "TtsEngine"
        private const val ANDROID_TTS_ASSETS_DIR = "android_tts_assets"
        private const val ASSET_TTS_CACHE_DIR = "supertonic_asset_cache"
        private const val SUPERTONIC_MODEL_DIR = "supertonic_3"
        private val SUPERTONIC_MODEL_DIR_NAMES = listOf(
            "supertonic_3",
            "supertonic-3",
            "supertonic3",
            "SuperTonic_3",
            "SuperTonic3"
        )
        private const val DEFAULT_VOICE_NAME = VoiceProfileParser.DEFAULT_VOICE_NAME
        private const val DEFAULT_LANGUAGE = "en"
        private const val DEFAULT_TOTAL_STEPS = 5
        private const val DEFAULT_SPEED = 1.05f
        private const val MAX_CHUNK_LENGTH = 300
        private const val SILENCE_DURATION_SECONDS = 0.3f
        private const val SUPERTONIC_THREADS = 4
    }
}
