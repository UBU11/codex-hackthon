package com.example.omu.audio

import com.example.omu.core.AppConstants
import java.io.ByteArrayOutputStream

class PhraseBuffer {
    private val output = ByteArrayOutputStream(AppConstants.FRAME_BYTES * 64)
    private var frameCount = 0

    val isEmpty: Boolean
        @Synchronized get() = output.size() == 0

    val durationMs: Int
        @Synchronized get() = frameCount * AppConstants.FRAME_SIZE_MS

    @Synchronized
    fun append(frame: ByteArray) {
        output.write(frame, 0, frame.size)
        frameCount++
    }

    @Synchronized
    fun extractAndClear(): ByteArray {
        val audio = output.toByteArray()
        clear()
        return audio
    }

    @Synchronized
    fun clear() {
        output.reset()
        frameCount = 0
    }
}
