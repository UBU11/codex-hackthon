package com.example.omu.core

object AppConstants {
    const val SAMPLE_RATE = 16000
    const val FRAME_SIZE_MS = 32
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000
    const val BYTES_PER_SAMPLE = 2
    const val FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE
    const val SILENCE_THRESHOLD_MS = 400
    const val SPEECH_START_THRESHOLD = 0.5f
    const val SPEECH_STOP_THRESHOLD = 0.35f
    const val SPEECH_START_CONSECUTIVE_FRAMES = 2
    const val PRE_ROLL_FRAME_COUNT = 10
    const val MIN_TURN_DURATION_MS = 500
    const val MIN_VOICED_MS = 160
    const val MAX_TURN_DURATION_MS = 5000
    const val TURN_COOLDOWN_MS = 350
    const val MIN_SPEECH_RMS = 0.015f
    const val MIN_SPEECH_PEAK = 0.06f
    const val MIN_CONTINUING_SPEECH_RMS = 0.01f
    const val MIN_CONTINUING_SPEECH_PEAK = 0.04f
}
