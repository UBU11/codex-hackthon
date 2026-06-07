package com.example.omu.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.example.omu.core.AppConstants
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class EnrollmentCaptureState(
    val isRecording: Boolean,
    val durationCapturedMs: Int
)

class EnrollmentRecorder {
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun record(
        durationMs: Int = DEFAULT_CAPTURE_DURATION_MS,
        onStateChanged: (EnrollmentCaptureState) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        val sampleRate = AppConstants.SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val targetByteCount = sampleRate * BYTES_PER_SAMPLE * durationMs / MILLIS_PER_SECOND
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            throw IllegalStateException("Enrollment recorder min buffer is invalid: $minBufferSize")
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                maxOf(minBufferSize, AppConstants.FRAME_BYTES * BUFFER_FRAME_COUNT)
            )
        } catch (securityException: SecurityException) {
            throw IllegalStateException("Microphone permission is required for voice enrollment", securityException)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("Enrollment recorder failed to initialize")
        }

        val output = ByteArrayOutputStream(targetByteCount)
        val readBuffer = ByteArray(AppConstants.FRAME_BYTES * BUFFER_FRAME_COUNT)
        var lastProgressMs = -PROGRESS_INTERVAL_MS

        try {
            recorder.startRecording()
            onStateChanged(EnrollmentCaptureState(isRecording = true, durationCapturedMs = 0))

            while (output.size() < targetByteCount) {
                currentCoroutineContext().ensureActive()
                val remaining = targetByteCount - output.size()
                val bytesRead = recorder.read(readBuffer, 0, minOf(readBuffer.size, remaining))
                if (bytesRead < 0) {
                    throw IllegalStateException("Enrollment recorder read failed: $bytesRead")
                }
                if (bytesRead == 0) continue

                output.write(readBuffer, 0, bytesRead)
                val progressMs = output.size() * MILLIS_PER_SECOND / (sampleRate * BYTES_PER_SAMPLE)
                if (progressMs - lastProgressMs >= PROGRESS_INTERVAL_MS || output.size() >= targetByteCount) {
                    lastProgressMs = progressMs
                    onStateChanged(
                        EnrollmentCaptureState(
                            isRecording = true,
                            durationCapturedMs = progressMs.coerceAtMost(durationMs)
                        )
                    )
                }
            }

            output.toByteArray()
        } finally {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to stop enrollment recorder", error)
            }
            recorder.release()
            onStateChanged(
                EnrollmentCaptureState(
                    isRecording = false,
                    durationCapturedMs = output.size() * MILLIS_PER_SECOND / (sampleRate * BYTES_PER_SAMPLE)
                )
            )
        }
    }

    companion object {
        const val DEFAULT_CAPTURE_DURATION_MS = 5_000
        private const val TAG = "EnrollmentRecorder"
        private const val BUFFER_FRAME_COUNT = 4
        private const val BYTES_PER_SAMPLE = 2
        private const val MILLIS_PER_SECOND = 1_000
        private const val PROGRESS_INTERVAL_MS = 100
    }
}
