package com.example.omu.pipeline

import com.example.omu.audio.PhraseBuffer
import com.example.omu.core.AppConstants
import com.example.omu.ml.VadEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

enum class TurnState {
    IDLE,
    SPEAKING,
    SYSTEM_SPEAKING,
    TURN_COMPLETE
}

sealed class TurnEvent {
    data class StateChanged(val state: TurnState) : TurnEvent()
    data class VadScore(val confidence: Float) : TurnEvent()
    data class VoiceStarted(val confidence: Float) : TurnEvent()
    data class PauseDetected(val durationMs: Int, val confidence: Float) : TurnEvent()
    data class VoiceStopped(val confidence: Float) : TurnEvent()
    data class TurnComplete(val audioPcm16: ByteArray, val durationMs: Int) : TurnEvent()
}

class TurnStateMachine(
    private val vadEngine: VadEngine,
    private val phraseBuffer: PhraseBuffer = PhraseBuffer(),
    private val isSystemSpeaking: () -> Boolean = { false }
) {
    fun process(audioFrames: Flow<ByteArray>): Flow<TurnEvent> = flow {
        var state = TurnState.IDLE
        var silenceMs = 0
        var voicedMs = 0
        var startCandidateFrames = 0
        var cooldownMs = 0
        val preRollFrames = ArrayDeque<ByteArray>(AppConstants.PRE_ROLL_FRAME_COUNT)

        phraseBuffer.clear()
        emit(TurnEvent.StateChanged(state))

        audioFrames.collect { frame ->
            if (isSystemSpeaking()) {
                if (state != TurnState.SYSTEM_SPEAKING) {
                    state = TurnState.SYSTEM_SPEAKING
                    silenceMs = 0
                    voicedMs = 0
                    startCandidateFrames = 0
                    cooldownMs = 0
                    phraseBuffer.clear()
                    preRollFrames.clear()
                    vadEngine.reset()
                    emit(TurnEvent.StateChanged(state))
                }
                return@collect
            }

            if (state == TurnState.SYSTEM_SPEAKING) {
                state = TurnState.IDLE
                vadEngine.reset()
                emit(TurnEvent.StateChanged(state))
            }

            if (frame.size != AppConstants.FRAME_BYTES) {
                return@collect
            }

            val confidence = vadEngine.confidenceFromPcm(frame)
            val audioLevel = frameLevel(frame)
            val isLikelySpeech = confidence > AppConstants.SPEECH_START_THRESHOLD &&
                audioLevel.rms >= AppConstants.MIN_SPEECH_RMS &&
                audioLevel.peak >= AppConstants.MIN_SPEECH_PEAK
            val isContinuingSpeech = confidence >= AppConstants.SPEECH_STOP_THRESHOLD &&
                audioLevel.rms >= AppConstants.MIN_CONTINUING_SPEECH_RMS &&
                audioLevel.peak >= AppConstants.MIN_CONTINUING_SPEECH_PEAK
            emit(TurnEvent.VadScore(confidence))

            if (cooldownMs > 0 && state == TurnState.IDLE) {
                cooldownMs = (cooldownMs - AppConstants.FRAME_SIZE_MS).coerceAtLeast(0)
                rememberPreRollFrame(preRollFrames, frame)
                return@collect
            }

            when (state) {
                TurnState.IDLE -> {
                    rememberPreRollFrame(preRollFrames, frame)
                    if (isLikelySpeech) {
                        startCandidateFrames++
                    } else if (isContinuingSpeech) {
                        startCandidateFrames = (startCandidateFrames - 1).coerceAtLeast(0)
                    } else {
                        startCandidateFrames = 0
                    }

                    if (startCandidateFrames >= AppConstants.SPEECH_START_CONSECUTIVE_FRAMES) {
                        state = TurnState.SPEAKING
                        phraseBuffer.clear()
                        preRollFrames.forEach { phraseBuffer.append(it) }
                        voicedMs = startCandidateFrames * AppConstants.FRAME_SIZE_MS
                        silenceMs = 0
                        emit(TurnEvent.StateChanged(state))
                        emit(TurnEvent.VoiceStarted(confidence))
                    }
                }

                TurnState.SPEAKING -> {
                    phraseBuffer.append(frame)
                    if (isLikelySpeech) {
                        voicedMs += AppConstants.FRAME_SIZE_MS
                    }

                    if (isContinuingSpeech) {
                        silenceMs = 0
                    } else {
                        silenceMs += AppConstants.FRAME_SIZE_MS
                        emit(TurnEvent.PauseDetected(silenceMs, confidence))
                    }

                    if (silenceMs >= AppConstants.SILENCE_THRESHOLD_MS ||
                        phraseBuffer.durationMs >= AppConstants.MAX_TURN_DURATION_MS
                    ) {
                        state = TurnState.TURN_COMPLETE
                        emit(TurnEvent.StateChanged(state))
                        emit(TurnEvent.VoiceStopped(confidence))

                        val durationMs = phraseBuffer.durationMs
                        if (durationMs >= AppConstants.MIN_TURN_DURATION_MS &&
                            voicedMs >= AppConstants.MIN_VOICED_MS &&
                            !phraseBuffer.isEmpty
                        ) {
                            emit(
                                TurnEvent.TurnComplete(
                                    phraseBuffer.extractAndClear(),
                                    durationMs
                                )
                            )
                        } else {
                            phraseBuffer.clear()
                        }

                        silenceMs = 0
                        voicedMs = 0
                        startCandidateFrames = 0
                        cooldownMs = AppConstants.TURN_COOLDOWN_MS
                        preRollFrames.clear()
                        vadEngine.reset()
                        state = TurnState.IDLE
                        emit(TurnEvent.StateChanged(state))
                    }
                }

                TurnState.SYSTEM_SPEAKING -> Unit
                TurnState.TURN_COMPLETE -> Unit
            }
        }
    }.flowOn(Dispatchers.Default)

    private fun rememberPreRollFrame(preRollFrames: ArrayDeque<ByteArray>, frame: ByteArray) {
        if (preRollFrames.size == AppConstants.PRE_ROLL_FRAME_COUNT) {
            preRollFrames.removeFirst()
        }
        preRollFrames.addLast(frame)
    }

    private fun frameLevel(frame: ByteArray): FrameLevel {
        var byteIndex = 0
        var peak = 0
        var squareSum = 0.0
        while (byteIndex + 1 < frame.size) {
            val sample = (frame[byteIndex + 1].toInt() shl 8) or
                (frame[byteIndex].toInt() and 0xff)
            val magnitude = kotlin.math.abs(sample)
            if (magnitude > peak) {
                peak = magnitude
            }
            squareSum += sample.toDouble() * sample.toDouble()
            byteIndex += AppConstants.BYTES_PER_SAMPLE
        }

        val sampleCount = frame.size / AppConstants.BYTES_PER_SAMPLE
        val rms = if (sampleCount > 0) {
            kotlin.math.sqrt(squareSum / sampleCount).toFloat() / PCM_16_NORMALIZER
        } else {
            0f
        }
        return FrameLevel(
            rms = rms,
            peak = peak / PCM_16_NORMALIZER
        )
    }

    private data class FrameLevel(
        val rms: Float,
        val peak: Float
    )

    private companion object {
        private const val PCM_16_NORMALIZER = 32768f
    }
}
