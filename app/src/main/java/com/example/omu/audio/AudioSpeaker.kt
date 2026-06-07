package com.example.omu.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class AudioSpeaker(
    private val sampleRate: Int = SAMPLE_RATE_HZ
) {
    private val playbackLock = Any()
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var audioTrack: AudioTrack? = null

    fun playRawFloats(samples: FloatArray) {
        if (samples.isEmpty()) return

        synchronized(playbackLock) {
            stopRequested.set(false)
            val track = ensureAudioTrack()
            val shortBuffer = ShortArray(WRITE_CHUNK_SAMPLES)
            var sampleOffset = 0
            var submittedFrames = 0

            resetTrack(track)
            track.play()

            try {
                while (sampleOffset < samples.size && !stopRequested.get()) {
                    val chunkSize = min(WRITE_CHUNK_SAMPLES, samples.size - sampleOffset)
                    for (index in 0 until chunkSize) {
                        shortBuffer[index] = samples[sampleOffset + index].toPcm16()
                    }

                    var chunkOffset = 0
                    while (chunkOffset < chunkSize && !stopRequested.get()) {
                        val written = track.write(
                            shortBuffer,
                            chunkOffset,
                            chunkSize - chunkOffset,
                            AudioTrack.WRITE_BLOCKING
                        )
                        if (written <= 0) break
                        chunkOffset += written
                        submittedFrames += written
                    }
                    sampleOffset += chunkSize
                }

                waitForDrain(track, submittedFrames)
            } finally {
                runCatching { track.pause() }
                runCatching { track.stop() }
                runCatching { track.flush() }
            }
        }
    }

    fun stop() {
        stopRequested.set(true)
        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    fun close() {
        stop()
        synchronized(playbackLock) {
            audioTrack?.release()
            audioTrack = null
        }
    }

    private fun ensureAudioTrack(): AudioTrack {
        audioTrack?.let { return it }

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBufferSize, WRITE_CHUNK_SAMPLES * Short.SIZE_BYTES * 2)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { audioTrack = it }
    }

    private fun resetTrack(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
        if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
            runCatching { track.stop() }
        }
        runCatching { track.flush() }
    }

    private fun waitForDrain(track: AudioTrack, submittedFrames: Int) {
        while (!stopRequested.get() && track.playbackHeadPosition < submittedFrames) {
            Thread.sleep(DRAIN_POLL_MS)
        }
    }

    private fun Float.toPcm16(): Short {
        val quantized = floor(coerceIn(-1f, 1f) * PCM_16_MAX).toInt()
        return quantized.coerceIn(PCM_16_MIN, PCM_16_MAX).toShort()
    }

    private companion object {
        private const val SAMPLE_RATE_HZ = 44100
        private const val WRITE_CHUNK_SAMPLES = 2048
        private const val DRAIN_POLL_MS = 10L
        private const val PCM_16_MIN = -32768
        private const val PCM_16_MAX = 32767
    }
}
