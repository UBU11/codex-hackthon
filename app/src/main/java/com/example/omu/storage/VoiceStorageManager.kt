package com.example.omu.storage

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File

class VoiceStorageManager(context: Context) {
    private val voiceDirectory = File(context.applicationContext.filesDir, VOICE_DIRECTORY_NAME)

    @Synchronized
    fun saveUserVoiceProfile(name: String, embedding: FloatArray) {
        require(embedding.isNotEmpty()) { "Voice profile embedding is empty" }

        voiceDirectory.mkdirs()
        val targetFile = profileFile(name)
        DataOutputStream(targetFile.outputStream().buffered()).use { output ->
            output.writeUTF(FILE_MAGIC)
            output.writeInt(FILE_VERSION)
            output.writeInt(embedding.size)
            embedding.forEach(output::writeFloat)
        }
    }

    @Synchronized
    fun getUserVoiceProfile(name: String): FloatArray? {
        val targetFile = profileFile(name)
        if (!targetFile.isFile || targetFile.length() <= 0L) return null

        return runCatching {
            DataInputStream(targetFile.inputStream().buffered()).use { input ->
                require(input.readUTF() == FILE_MAGIC) { "Invalid voice profile file" }
                require(input.readInt() == FILE_VERSION) { "Unsupported voice profile version" }

                val size = input.readInt()
                require(size in 1..MAX_EMBEDDING_VALUES) { "Invalid voice profile size: $size" }

                FloatArray(size) {
                    try {
                        input.readFloat()
                    } catch (error: EOFException) {
                        throw IllegalArgumentException("Voice profile file is truncated", error)
                    }
                }
            }
        }.getOrNull()
    }

    private fun profileFile(name: String): File {
        return File(voiceDirectory, "${sanitizeName(name)}.$FILE_EXTENSION")
    }

    private fun sanitizeName(name: String): String {
        val sanitized = name
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
        return sanitized.ifBlank { DEFAULT_PROFILE_NAME }
    }

    companion object {
        const val DEFAULT_PROFILE_NAME = "my_voice"
        private const val VOICE_DIRECTORY_NAME = "user_voice_profiles"
        private const val FILE_EXTENSION = "bin"
        private const val FILE_MAGIC = "OMUVOICE"
        private const val FILE_VERSION = 1
        private const val MAX_EMBEDDING_VALUES = 1_000_000
    }
}
