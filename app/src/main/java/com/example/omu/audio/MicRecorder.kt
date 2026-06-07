package com.example.omu.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.omu.core.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive


class MicRecorder {
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun start(): Flow<ByteArray> = flow {
        val sampleRate = AppConstants.SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            audioFormat
        )

        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "AudioRecord min buffer is invalid: $minBufferSize")
            return@flow
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
            Log.e(TAG, "Microphone permission is missing", securityException)
            return@flow
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Log.e(TAG, "AudioRecord failed to initialize")
            return@flow
        }

        val readBuffer = ByteArray(AppConstants.FRAME_BYTES)

        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                var offset = 0
                while (offset < AppConstants.FRAME_BYTES && currentCoroutineContext().isActive) {
                    val bytesRead = recorder.read(
                        readBuffer,
                        offset,
                        AppConstants.FRAME_BYTES - offset
                    )
                    if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord read failed: $bytesRead")
                        return@flow
                    }
                    offset += bytesRead
                }

                if (offset == AppConstants.FRAME_BYTES) {
                    emit(readBuffer.copyOf())
                }
            }
        } catch (securityException: SecurityException) {
            Log.e(TAG, "Microphone permission was revoked while recording", securityException)
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "MicRecorder"
        private const val BUFFER_FRAME_COUNT = 4
    }
}
