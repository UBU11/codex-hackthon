package com.example.omu.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import java.io.File


class GemmaEngine(private val context: Context, private val modelPath: String) {
    private var engine: Engine? = null
    private var customConfig: EngineConfig? = null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        val config = customConfig ?: createHardwareConfig(
            backend = Backend.GPU(),
            audioBackend = Backend.CPU()
        )
        engine = initializeWithFallback(config)
    }

    fun generateResponseAsync(contents: Contents, onChunk: (Message) -> Unit) {
        val activeEngine = engine ?: return
        val conversation = activeEngine.createConversation()
        
        conversation.sendMessageAsync(contents, object : MessageCallback {
            override fun onMessage(message: Message) {
                onChunk(message)
            }

            override fun onDone() {
                conversation.close()
            }

            override fun onError(throwable: Throwable) {
                conversation.close()
            }
        })
    }

    fun generateResponse(contents: Contents): Flow<String>? {
        val activeEngine = engine ?: return null
        val conversation = activeEngine.createConversation()
        return conversation.sendMessageAsync(contents)
            .map { it.text }
            .onCompletion { conversation.close() }
    }

    fun generateResponse(prompt: String): Flow<String>? {
        return generateResponse(Contents.of(Content.Text(prompt)))
    }

    fun translateAudio(audioPcm16: ByteArray): Flow<String>? {
        val wavBytes = pcm16MonoToWav(audioPcm16)
        writeTurnWav(wavBytes)
        val contents = Contents.of(
            Content.Text(TRANSLATION_PROMPT),
            Content.AudioBytes(wavBytes)
        )
        return generateResponse(contents)
    }

    fun translateMalayalamText(text: String): Flow<String>? {
        return generateResponse(
            "$TEXT_TRANSLATION_PROMPT\n\n$text"
        )
    }

    fun close() {
        engine?.close()
        engine = null
    }

    private fun createHardwareConfig(
        backend: Backend,
        audioBackend: Backend = Backend.CPU()
    ): EngineConfig {
        return EngineConfig(
            modelPath = modelPath,
            backend = backend,
            audioBackend = audioBackend,
            cacheDir = context.cacheDir.path
        )
    }

    private fun initializeWithFallback(config: EngineConfig): Engine {
        val primaryEngine = Engine(config)
        try {
            primaryEngine.initialize()
            return primaryEngine
        } catch (primaryError: Throwable) {
            runCatching { primaryEngine.close() }
                .onFailure { closeError ->
                    Log.w(TAG, "Failed to close uninitialized primary Gemma engine", closeError)
                }
            if (config.backend is Backend.GPU) {
                throw IllegalStateException("Gemma engine failed to initialize on GPU", primaryError)
            }
            Log.w(TAG, "Primary Gemma backend failed; retrying with GPU", primaryError)
            val gpuEngine = Engine(createHardwareConfig(backend = Backend.GPU()))
            try {
                gpuEngine.initialize()
                return gpuEngine
            } catch (gpuError: Throwable) {
                runCatching { gpuEngine.close() }
                    .onFailure { closeError ->
                        Log.w(TAG, "Failed to close uninitialized GPU Gemma engine", closeError)
                    }
                Log.e(TAG, "GPU Gemma backend failed after primary backend failure", gpuError)
                throw IllegalStateException(
                    "Gemma engine failed to initialize on NPU or GPU",
                    gpuError
                ).also { it.addSuppressed(primaryError) }
            }
        }
    }

    private fun pcm16MonoToWav(pcm16: ByteArray): ByteArray {
        val wav = ByteArray(WAV_HEADER_BYTES + pcm16.size)
        writeAscii(wav, 0, "RIFF")
        writeIntLe(wav, 4, wav.size - 8)
        writeAscii(wav, 8, "WAVE")
        writeAscii(wav, 12, "fmt ")
        writeIntLe(wav, 16, 16)
        writeShortLe(wav, 20, 1)
        writeShortLe(wav, 22, 1)
        writeIntLe(wav, 24, AUDIO_SAMPLE_RATE_HZ)
        writeIntLe(wav, 28, AUDIO_SAMPLE_RATE_HZ * AUDIO_CHANNELS * AUDIO_BYTES_PER_SAMPLE)
        writeShortLe(wav, 32, AUDIO_CHANNELS * AUDIO_BYTES_PER_SAMPLE)
        writeShortLe(wav, 34, AUDIO_BITS_PER_SAMPLE)
        writeAscii(wav, 36, "data")
        writeIntLe(wav, 40, pcm16.size)
        pcm16.copyInto(wav, WAV_HEADER_BYTES)
        return wav
    }

    private fun writeTurnWav(wavBytes: ByteArray): File? {
        val directory = context.getExternalFilesDir(null) ?: context.cacheDir
        val file = File(directory, LAST_TURN_WAV_FILENAME)
        return runCatching {
            file.writeBytes(wavBytes)
            Log.i(
                TAG,
                "Wrote Gemma audio turn: ${file.absolutePath} (${wavBytes.size} bytes)"
            )
            file
        }.onFailure { error ->
            Log.w(TAG, "Failed to write Gemma audio turn WAV", error)
        }.getOrNull()
    }

    private fun writeAscii(target: ByteArray, offset: Int, value: String) {
        for (index in value.indices) {
            target[offset + index] = value[index].code.toByte()
        }
    }

    private fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun writeShortLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    companion object {
        private val MODEL_FILENAMES = listOf(
            "gemma-4-e2b-audio.litertlm",
            "gemma-4-E2B-it.litertlm",
            "gemma-4-e2b-it.litertlm"
        )
        private const val TAG = "GemmaProject"
        private const val WAV_HEADER_BYTES = 44
        private const val AUDIO_SAMPLE_RATE_HZ = 16000
        private const val AUDIO_CHANNELS = 1
        private const val AUDIO_BYTES_PER_SAMPLE = 2
        private const val AUDIO_BITS_PER_SAMPLE = AUDIO_BYTES_PER_SAMPLE * 8
        private const val LAST_TURN_WAV_FILENAME = "last_turn.wav"
        private const val TRANSLATION_PROMPT =
            "Task: translate the attached spoken Malayalam audio into natural English. Output English only. Do not output Malayalam script. Do not transcribe. Do not answer the speaker. Return only the English translation. If the audio has no clear speech or is missing, return exactly: No clear speech detected."
        private const val TEXT_TRANSLATION_PROMPT =
            "Translate the following Malayalam text into natural English. Output English only. Do not include Malayalam script, labels, explanations, or quotes. If it is already English, return it unchanged."

        fun getGemmaModelFile(context: Context): File? {
            val externalFilesDir = context.getExternalFilesDir(null)
            if (externalFilesDir != null) {
                MODEL_FILENAMES.forEach { fileName ->
                    val modelFile = File(externalFilesDir, fileName)
                    if (modelFile.exists() && modelFile.length() > 0) {
                        return modelFile
                    }
                }

                val gemmaModel = externalFilesDir
                    .walkTopDown()
                    .firstOrNull {
                        it.isFile &&
                            it.length() > 0 &&
                            it.extension.equals("litertlm", ignoreCase = true) &&
                            it.name.contains("gemma", ignoreCase = true)
                    }
                if (gemmaModel != null) {
                    return gemmaModel
                }
            }
            return null
        }

        suspend fun initializeGemmaEngine(context: Context): GemmaEngine? {
            val modelFile = getGemmaModelFile(context)

            if (modelFile == null) {
                Log.e(TAG, "Gemma model file not found in the app Android/data files directory.")
                return null
            }
            Log.i(TAG, "Using Gemma model: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                audioBackend = Backend.CPU(),
                cacheDir = context.cacheDir.path
            )

            val gemmaEngine = create(context, engineConfig)
            try {
                gemmaEngine.initialize()
                Log.i(TAG, "Gemma 4 initialized successfully from local storage!")
                return gemmaEngine
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize Gemma engine from ${modelFile.absolutePath}", e)
                return null
            }
        }

        fun create(context: Context, config: EngineConfig): GemmaEngine {
            val instance = GemmaEngine(context, config.modelPath)
            instance.customConfig = config
            return instance
        }
    }
}

val Message.text: String
    get() = contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }
        .ifBlank { toString() }
