package com.example.omu.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.example.omu.core.AppConstants
import java.io.File
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpeakerEncoderEngine(context: Context) {
    private val appContext = context.applicationContext
    private val assetManager = appContext.assets
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    suspend fun prepare() {
        stageEncoderModel()
    }

    suspend fun extractEmbedding(audioPcm16: ByteArray): FloatArray {
        val modelFile = stageEncoderModel()
        return withContext(Dispatchers.Default) {
            val waveform = pcm16ToFloat(audioPcm16)
            require(waveform.isNotEmpty()) { "Enrollment audio is empty" }

            val options = createSessionOptions()
            val session = try {
                env.createSession(modelFile.absolutePath, options)
            } finally {
                runCatching { options.close() }
            }

            try {
                runEncoder(session, waveform)
            } finally {
                runCatching { session.close() }
            }
        }
    }

    private suspend fun stageEncoderModel(): File = withContext(Dispatchers.IO) {
        val assetPath = ENCODER_ASSET_CANDIDATES.firstOrNull(::assetCanOpen)
            ?: throw IllegalStateException(
                "Missing SuperTonic speaker encoder asset: $ENCODER_ASSET_NAME"
            )
        val targetFile = File(appContext.filesDir, "$ENCODER_CACHE_DIR/$ENCODER_ASSET_NAME")

        if (targetFile.length() <= 0L) {
            targetFile.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        targetFile
    }

    private fun runEncoder(
        session: OrtSession,
        waveform: FloatArray
    ): FloatArray {
        val inputName = session.inputNames.firstOrNull()
            ?: throw IllegalStateException("Speaker encoder has no inputs")
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(waveform),
            longArrayOf(1L, waveform.size.toLong())
        )

        try {
            val result = session.run(mapOf(inputName to inputTensor))
            try {
                return flattenFloatOutput(result[0].value)
            } finally {
                result.close()
            }
        } finally {
            inputTensor.close()
        }
    }

    private fun pcm16ToFloat(audioPcm16: ByteArray): FloatArray {
        val sampleCount = audioPcm16.size / BYTES_PER_SAMPLE
        return FloatArray(sampleCount) { index ->
            val low = audioPcm16[index * BYTES_PER_SAMPLE].toInt() and 0xFF
            val high = audioPcm16[index * BYTES_PER_SAMPLE + 1].toInt()
            val sample = (high shl 8) or low
            sample.toShort() / PCM_16_SCALE
        }
    }

    private fun flattenFloatOutput(value: Any?): FloatArray {
        return when (value) {
            is FloatArray -> value.copyOf()
            is Array<*> -> {
                val chunks = value.map { child -> flattenFloatOutput(child) }
                val totalSize = chunks.sumOf { chunk -> chunk.size }
                val output = FloatArray(totalSize)
                var offset = 0
                chunks.forEach { chunk ->
                    chunk.copyInto(output, destinationOffset = offset)
                    offset += chunk.size
                }
                output
            }

            else -> throw IllegalStateException(
                "Speaker encoder returned unsupported output: ${value?.javaClass?.name ?: "null"}"
            )
        }
    }

    private fun assetCanOpen(assetPath: String): Boolean {
        return runCatching {
            assetManager.open(assetPath).use { stream ->
                stream.read() >= 0
            }
        }.getOrDefault(false)
    }

    private fun createSessionOptions(): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(ENCODER_THREADS)
            runCatching {
                addXnnpack(mapOf("intra_op_num_threads" to ENCODER_THREADS.toString()))
            }.onFailure { error ->
                Log.w(TAG, "XNNPACK unavailable for speaker encoder; using default CPU", error)
            }
        }
    }

    companion object {
        private const val TAG = "SpeakerEncoderEngine"
        private const val ENCODER_ASSET_NAME = "supertonic_3_encoder.onnx"
        private const val ENCODER_CACHE_DIR = "supertonic_encoder_cache"
        private const val BYTES_PER_SAMPLE = 2
        private const val PCM_16_SCALE = 32768.0f
        private const val ENCODER_THREADS = 2
        private val ENCODER_ASSET_CANDIDATES = listOf(
            ENCODER_ASSET_NAME,
            "android_tts_assets/onnx/$ENCODER_ASSET_NAME",
            "android_tts_assets/supertonic_3/onnx/$ENCODER_ASSET_NAME"
        )

        const val EXPECTED_SAMPLE_RATE = AppConstants.SAMPLE_RATE
    }
}
