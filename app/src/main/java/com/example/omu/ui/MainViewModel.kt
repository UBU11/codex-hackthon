package com.example.omu.ui

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.omu.audio.AudioSpeaker
import com.example.omu.audio.EnrollmentRecorder
import com.example.omu.audio.MicRecorder
import com.example.omu.ml.GemmaEngine
import com.example.omu.ml.SpeakerEncoderEngine
import com.example.omu.ml.TtsEngine
import com.example.omu.ml.VadEngine
import com.example.omu.ml.VoiceProfile
import com.example.omu.ml.VoiceProfileInfo
import com.example.omu.ml.VoiceProfileParser
import com.example.omu.pipeline.TurnEvent
import com.example.omu.pipeline.TurnState
import com.example.omu.pipeline.TurnStateMachine
import com.example.omu.storage.VoiceStorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val ENROLLMENT_SCRIPT =
    "The quick brown fox jumps over the lazy dog while Omu learns my clear speaking voice."

data class TranslateUiState(
    val status: String = "Ready",
    val translatedText: String = "",
    val vadConfidence: Float = 0f,
    val pauseMs: Int = 0,
    val turnsDetected: Int = 0,
    val currentTurnDurationMs: Int = 0,
    val pipelineState: TurnState = TurnState.IDLE,
    val isListening: Boolean = false,
    val isTranslating: Boolean = false,
    val isSpeaking: Boolean = false,
    val availableVoices: List<VoiceProfileInfo> = emptyList(),
    val selectedVoiceId: String = "",
    val selectedVoiceLabel: String = VoiceProfileParser.DEFAULT_VOICE_NAME,
    val isVoiceLoading: Boolean = false,
    val isEnrollmentRecording: Boolean = false,
    val isEnrollmentProcessing: Boolean = false,
    val enrollmentDurationMs: Int = 0,
    val enrollmentTargetMs: Int = EnrollmentRecorder.DEFAULT_CAPTURE_DURATION_MS,
    val enrollmentScript: String = ENROLLMENT_SCRIPT,
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val micRecorder = MicRecorder()
    private val enrollmentRecorder = EnrollmentRecorder()
    private val audioSpeaker = AudioSpeaker()
    private val voiceProfileParser = VoiceProfileParser(application)
    private val voiceStorageManager = VoiceStorageManager(application)
    private val systemSpeaking = AtomicBoolean(false)
    private val _uiState = MutableStateFlow(TranslateUiState())
    private val _activeVoiceEmbedding = MutableStateFlow(FloatArray(0))
    private var vadEngine: VadEngine? = null
    private var gemmaEngine: GemmaEngine? = null
    private var ttsEngine: TtsEngine? = null
    @Volatile
    private var activeVoiceProfile: VoiceProfile? = null
    private var listeningJob: Job? = null
    private var translationJob: Job? = null
    private var ttsJob: Job? = null
    private var gemmaWarmupJob: Job? = null
    private var enrollmentJob: Job? = null
    private val vadInitMutex = Mutex()
    private val gemmaInitMutex = Mutex()
    private val voiceProfileMutex = Mutex()

    val uiState: StateFlow<TranslateUiState> = _uiState.asStateFlow()
    val activeVoiceEmbedding: StateFlow<FloatArray> = _activeVoiceEmbedding.asStateFlow()

    init {
        loadVoiceProfiles()
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { ensureVadEngine() }
                .onFailure { error ->
                    Log.w(TAG, "VAD warmup failed", error)
                }
        }
    }

    fun selectVoiceProfile(voiceId: String) {
        if (voiceId.isBlank() || voiceId == _uiState.value.selectedVoiceId) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isVoiceLoading = true,
                    error = null
                )
            }

            runCatching {
                loadVoiceProfile(voiceId)
            }.onSuccess { voiceProfile ->
                _uiState.update {
                    it.copy(
                        selectedVoiceId = voiceProfile.id,
                        selectedVoiceLabel = voiceProfile.label,
                        isVoiceLoading = false,
                        error = null
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to select voice profile", error)
                _uiState.update {
                    it.copy(
                        isVoiceLoading = false,
                        error = error.message ?: "Failed to load voice profile"
                    )
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startVoiceEnrollment() {
        if (enrollmentJob?.isActive == true) return

        stopListening()
        ttsJob?.cancel()
        ttsJob = null
        audioSpeaker.stop()
        systemSpeaking.set(false)

        enrollmentJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    status = "Preparing voice enrollment...",
                    isEnrollmentRecording = false,
                    isEnrollmentProcessing = true,
                    enrollmentDurationMs = 0,
                    error = null
                )
            }

            runCatching {
                val speakerEncoderEngine = SpeakerEncoderEngine(getApplication())
                speakerEncoderEngine.prepare()

                _uiState.update {
                    it.copy(
                        status = "Recording voice sample...",
                        isEnrollmentRecording = true,
                        isEnrollmentProcessing = false,
                        enrollmentDurationMs = 0,
                        error = null
                    )
                }

                val audioPcm16 = enrollmentRecorder.record(
                    durationMs = EnrollmentRecorder.DEFAULT_CAPTURE_DURATION_MS
                ) { captureState ->
                    _uiState.update {
                        it.copy(
                            isEnrollmentRecording = captureState.isRecording,
                            enrollmentDurationMs = captureState.durationCapturedMs
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        status = "Encoding voice...",
                        isEnrollmentRecording = false,
                        isEnrollmentProcessing = true,
                        enrollmentDurationMs = EnrollmentRecorder.DEFAULT_CAPTURE_DURATION_MS,
                        error = null
                    )
                }

                val embedding = speakerEncoderEngine.extractEmbedding(audioPcm16)
                val voiceProfile = createUserVoiceProfile(embedding)
                withContext(Dispatchers.IO) {
                    voiceStorageManager.saveUserVoiceProfile(USER_VOICE_STORAGE_NAME, embedding)
                }
                activateVoiceProfile(voiceProfile)
                voiceProfile
            }.onSuccess { voiceProfile ->
                _uiState.update {
                    it.copy(
                        status = "Voice enrolled",
                        availableVoices = withUserVoiceInfo(it.availableVoices),
                        selectedVoiceId = voiceProfile.id,
                        selectedVoiceLabel = voiceProfile.label,
                        isEnrollmentRecording = false,
                        isEnrollmentProcessing = false,
                        isVoiceLoading = false,
                        error = null
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    Log.d(TAG, "Voice enrollment cancelled")
                    throw error
                }

                Log.e(TAG, "Voice enrollment failed", error)
                _uiState.update {
                    it.copy(
                        status = if (it.isListening) "Listening..." else "Ready",
                        isEnrollmentRecording = false,
                        isEnrollmentProcessing = false,
                        error = error.message ?: "Voice enrollment failed"
                    )
                }
            }
        }
    }

    fun cancelVoiceEnrollment() {
        enrollmentJob?.cancel()
        enrollmentJob = null
        _uiState.update {
            it.copy(
                status = if (it.isListening) "Listening..." else "Ready",
                isEnrollmentRecording = false,
                isEnrollmentProcessing = false,
                enrollmentDurationMs = 0
            )
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        if (listeningJob?.isActive == true) return

        _uiState.update {
            it.copy(
                status = "Preparing audio...",
                isListening = true,
                pauseMs = 0,
                error = null
            )
        }

        warmGemmaEngine()
        listeningJob = viewModelScope.launch(Dispatchers.IO) {
            val vad = runCatching { ensureVadEngine() }
                .onFailure { error ->
                    Log.e(TAG, "Failed to initialize VAD", error)
                    _uiState.update {
                        it.copy(
                            status = "VAD unavailable",
                            isListening = false,
                            error = error.message ?: "VAD failed to initialize"
                        )
                    }
                }
                .getOrNull() ?: return@launch

            _uiState.update {
                it.copy(
                    status = "Listening...",
                    error = null
                )
            }

            runCatching {
                TurnStateMachine(
                    vadEngine = vad,
                    isSystemSpeaking = { systemSpeaking.get() }
                )
                    .process(micRecorder.start())
                    .collect(::handleTurnEvent)
            }.onFailure { error ->
                if (error is CancellationException) {
                    Log.d(TAG, "Listening pipeline cancelled")
                    return@onFailure
                }
                Log.e(TAG, "Listening pipeline stopped", error)
                _uiState.update {
                    it.copy(
                        status = "Stopped",
                        isListening = false,
                        error = error.message
                    )
                }
            }
        }
    }

    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        ttsJob?.cancel()
        ttsJob = null
        systemSpeaking.set(false)
        audioSpeaker.stop()
        vadEngine?.reset()
        _uiState.update {
            it.copy(
                status = "Stopped",
                pipelineState = TurnState.IDLE,
                isListening = false,
                isSpeaking = false,
                pauseMs = 0
            )
        }
    }

    fun clearTranscript() {
        _uiState.update {
            it.copy(
                translatedText = "",
                turnsDetected = 0,
                currentTurnDurationMs = 0,
                error = null
            )
        }
    }

    private suspend fun ensureVadEngine(): VadEngine {
        return vadInitMutex.withLock {
            vadEngine ?: withContext(Dispatchers.Default) {
                VadEngine(getApplication()).also { engine ->
                    engine.warmUp()
                    vadEngine = engine
                }
            }
        }
    }

    private fun handleTurnEvent(event: TurnEvent) {
        when (event) {
            is TurnEvent.StateChanged -> {
                _uiState.update {
                    it.copy(
                        pipelineState = event.state,
                        status = statusFor(
                            event.state,
                            it.isListening,
                            it.isTranslating,
                            it.isSpeaking
                        )
                    )
                }
            }

            is TurnEvent.VadScore -> {
                _uiState.update {
                    it.copy(vadConfidence = event.confidence)
                }
            }

            is TurnEvent.VoiceStarted -> {
                _uiState.update {
                    it.copy(
                        status = if (it.isSpeaking) "Speaking translation..." else "Speaking...",
                        pauseMs = 0,
                        error = null
                    )
                }
            }

            is TurnEvent.PauseDetected -> {
                _uiState.update {
                    it.copy(
                        status = if (it.isSpeaking) {
                            "Speaking translation..."
                        } else {
                            "Pause ${event.durationMs} ms"
                        },
                        pauseMs = event.durationMs,
                        vadConfidence = event.confidence
                    )
                }
            }

            is TurnEvent.VoiceStopped -> {
                _uiState.update {
                    it.copy(
                        status = if (it.isSpeaking) "Speaking translation..." else "Voice stopped",
                        vadConfidence = event.confidence
                    )
                }
            }

            is TurnEvent.TurnComplete -> {
                if (translationJob?.isActive == true || ttsJob?.isActive == true || systemSpeaking.get()) {
                    Log.d(TAG, "Skipping completed turn because translation or TTS is already in progress")
                    return
                }
                _uiState.update {
                    it.copy(
                        status = "Translating...",
                        turnsDetected = it.turnsDetected + 1,
                        currentTurnDurationMs = event.durationMs,
                        pauseMs = 0,
                        isTranslating = true
                    )
                }
                translateTurn(event.audioPcm16)
            }
        }
    }

    private fun translateTurn(audioPcm16: ByteArray) {
        translationJob = viewModelScope.launch(Dispatchers.IO) {
            val engine = ensureGemmaEngine()
            if (engine == null) {
                val modelPresent = GemmaEngine.getGemmaModelFile(getApplication()) != null
                _uiState.update {
                    it.copy(
                        status = if (it.isListening) "Listening..." else "Stopped",
                        isTranslating = false,
                        error = if (modelPresent) {
                            "Gemma model found, but the engine failed to initialize"
                        } else {
                            "Gemma model not found in this app's Android/data files directory"
                        }
                    )
                }
                return@launch
            }

            val response = StringBuilder()
            var textForSpeech: String? = null
            val translationFlow = engine.translateAudio(audioPcm16)
            if (translationFlow == null) {
                _uiState.update {
                    it.copy(
                        status = if (it.isListening) "Listening..." else "Stopped",
                        isTranslating = false,
                        error = "Gemma engine is not initialized"
                    )
                }
                return@launch
            }

            runCatching {
                translationFlow.collect { chunk ->
                    response.append(chunk)
                    val partial = response.toString().trim()
                    if (partial.isBlank() || containsMalayalamScript(partial)) {
                        return@collect
                    }
                    _uiState.update {
                        it.copy(
                            translatedText = partial,
                            isTranslating = true,
                            error = null
                        )
                    }
                }

                val finalText = response.toString().trim()
                val normalizedText = if (containsMalayalamScript(finalText)) {
                    Log.i(TAG, "Gemma returned Malayalam text; running English correction pass")
                    translateMalayalamText(engine, finalText)
                } else {
                    finalText
                }

                if (normalizedText.isNotBlank()) {
                    _uiState.update {
                        it.copy(
                            translatedText = normalizedText,
                            isTranslating = true,
                            error = null
                        )
                    }
                    textForSpeech = normalizedText
                }
            }.onFailure { error ->
                Log.e(TAG, "Translation failed", error)
                _uiState.update {
                    it.copy(
                        error = error.message ?: "Translation failed",
                        isTranslating = false
                    )
                }
            }

            _uiState.update {
                it.copy(
                    status = if (it.isListening) "Listening..." else "Stopped",
                    isTranslating = false
                )
            }

            textForSpeech?.let(::speakTranslation)
        }
    }

    private suspend fun translateMalayalamText(
        engine: GemmaEngine,
        malayalamText: String
    ): String {
        val correctionFlow = engine.translateMalayalamText(malayalamText) ?: return malayalamText
        val corrected = StringBuilder()

        _uiState.update {
            it.copy(
                status = "Converting to English...",
                translatedText = "",
                isTranslating = true,
                error = null
            )
        }

        correctionFlow.collect { chunk ->
            corrected.append(chunk)
            val partial = corrected.toString().trim()
            if (partial.isNotBlank()) {
                _uiState.update {
                    it.copy(
                        translatedText = partial,
                        isTranslating = true,
                        error = null
                    )
                }
            }
        }

        return corrected.toString().trim().ifBlank { malayalamText }
    }

    private fun containsMalayalamScript(text: String): Boolean {
        return text.any { character ->
            character.code in MALAYALAM_UNICODE_START..MALAYALAM_UNICODE_END
        }
    }

    private suspend fun ensureGemmaEngine(): GemmaEngine? {
        return gemmaInitMutex.withLock {
            gemmaEngine ?: GemmaEngine.initializeGemmaEngine(getApplication())?.also {
                gemmaEngine = it
            }
        }
    }

    private fun warmGemmaEngine() {
        if (gemmaEngine != null || gemmaWarmupJob?.isActive == true) return

        gemmaWarmupJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { ensureGemmaEngine() }
                .onFailure { error ->
                    Log.w(TAG, "Gemma warmup failed", error)
                }
        }
    }

    private suspend fun ensureTtsEngine(): TtsEngine {
        ttsEngine?.let { return it }
        return TtsEngine(getApplication()).also {
            ttsEngine = it
        }
    }

    private fun speakTranslation(text: String) {
        if (!shouldSpeakTranslation(text)) return

        ttsJob?.cancel()
        audioSpeaker.stop()
        ttsJob = viewModelScope.launch(Dispatchers.Default) {
            systemSpeaking.set(true)
            _uiState.update {
                it.copy(
                    status = "Speaking translation...",
                    pipelineState = TurnState.SYSTEM_SPEAKING,
                    isSpeaking = true,
                    error = null
                )
            }

            try {
                val voiceProfile = ensureActiveVoiceProfile()
                val audio = ensureTtsEngine().synthesize(text, voiceProfile)
                audioSpeaker.playRawFloats(audio)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "SuperTonic 3 TTS failed", error)
                _uiState.update {
                    it.copy(
                        error = error.message ?: "SuperTonic 3 TTS failed"
                    )
                }
            } finally {
                systemSpeaking.set(false)
                _uiState.update {
                    it.copy(
                        status = if (it.isListening) "Listening..." else "Stopped",
                        pipelineState = TurnState.IDLE,
                        isSpeaking = false
                    )
                }
            }
        }
    }

    private fun shouldSpeakTranslation(text: String): Boolean {
        val normalized = text.trim()
        return normalized.isNotBlank() &&
            !containsMalayalamScript(normalized) &&
            !normalized.equals(NO_CLEAR_SPEECH_MESSAGE, ignoreCase = true) &&
            !normalized.contains(PROVIDE_AUDIO_FILE_MESSAGE, ignoreCase = true)
    }

    private fun loadVoiceProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isVoiceLoading = true) }

            runCatching {
                val assetProfiles = voiceProfileParser.listVoiceProfiles()
                val savedVoiceProfile = loadSavedUserVoiceProfileOrNull()
                val profiles = if (savedVoiceProfile != null) {
                    withUserVoiceInfo(assetProfiles)
                } else {
                    assetProfiles
                }
                val selectedId = savedVoiceProfile?.id ?: profiles.firstOrNull {
                    it.id == VoiceProfileParser.DEFAULT_VOICE_ID
                }?.id ?: profiles.firstOrNull()?.id ?: VoiceProfileParser.DEFAULT_VOICE_ID
                val voiceProfile = savedVoiceProfile ?: loadVoiceProfile(selectedId)
                profiles to voiceProfile
            }.onSuccess { (profiles, voiceProfile) ->
                _uiState.update {
                    it.copy(
                        availableVoices = profiles,
                        selectedVoiceId = voiceProfile.id,
                        selectedVoiceLabel = voiceProfile.label,
                        isVoiceLoading = false,
                        error = null
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to load voice profiles", error)
                _uiState.update {
                    it.copy(
                        isVoiceLoading = false,
                        error = error.message ?: "Failed to load voice profiles"
                    )
                }
            }
        }
    }

    private suspend fun ensureActiveVoiceProfile(): VoiceProfile {
        activeVoiceProfile?.let { return it }
        val selectedId = _uiState.value.selectedVoiceId
            .ifBlank { VoiceProfileParser.DEFAULT_VOICE_ID }
        return loadVoiceProfile(selectedId)
    }

    private suspend fun loadVoiceProfile(voiceId: String): VoiceProfile {
        val voiceProfile = if (voiceId == USER_VOICE_ID) {
            loadSavedUserVoiceProfileOrNull()
                ?: throw IllegalStateException("Saved user voice profile is unavailable")
        } else {
            voiceProfileParser.loadVoiceProfile(voiceId)
        }
        activateVoiceProfile(voiceProfile)
        return voiceProfile
    }

    private suspend fun loadSavedUserVoiceProfileOrNull(): VoiceProfile? {
        val embedding = withContext(Dispatchers.IO) {
            voiceStorageManager.getUserVoiceProfile(USER_VOICE_STORAGE_NAME)
        } ?: return null
        return createUserVoiceProfile(embedding)
    }

    private suspend fun createUserVoiceProfile(embedding: FloatArray): VoiceProfile {
        val template = voiceProfileParser.loadVoiceProfile(VoiceProfileParser.DEFAULT_VOICE_ID)
        return VoiceProfileParser.createVoiceProfileFromEmbedding(
            id = USER_VOICE_ID,
            label = USER_VOICE_LABEL,
            embedding = embedding,
            template = template
        )
    }

    private suspend fun activateVoiceProfile(voiceProfile: VoiceProfile) {
        voiceProfileMutex.withLock {
            activeVoiceProfile = voiceProfile
            _activeVoiceEmbedding.value = voiceProfile.flattenEmbedding()
        }
    }

    private fun withUserVoiceInfo(profiles: List<VoiceProfileInfo>): List<VoiceProfileInfo> {
        return listOf(VoiceProfileInfo(USER_VOICE_ID, USER_VOICE_LABEL)) +
            profiles.filterNot { profile -> profile.id == USER_VOICE_ID }
    }

    private fun statusFor(
        state: TurnState,
        isListening: Boolean,
        isTranslating: Boolean,
        isSpeaking: Boolean
    ): String {
        if (isSpeaking) return "Speaking translation..."
        if (isTranslating) return "Translating..."
        return when {
            !isListening -> "Stopped"
            state == TurnState.IDLE -> "Listening..."
            state == TurnState.SPEAKING -> "Speaking..."
            state == TurnState.SYSTEM_SPEAKING -> "Speaking translation..."
            state == TurnState.TURN_COMPLETE -> "Turn complete"
            else -> "Ready"
        }
    }

    override fun onCleared() {
        listeningJob?.cancel()
        translationJob?.cancel()
        ttsJob?.cancel()
        gemmaWarmupJob?.cancel()
        enrollmentJob?.cancel()
        systemSpeaking.set(false)
        audioSpeaker.close()
        vadEngine?.close()
        gemmaEngine?.close()
        ttsEngine?.close()
        super.onCleared()
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val USER_VOICE_ID = "user_voice:my_voice"
        private const val USER_VOICE_LABEL = "My Voice"
        private const val USER_VOICE_STORAGE_NAME = VoiceStorageManager.DEFAULT_PROFILE_NAME
        private const val MALAYALAM_UNICODE_START = 0x0D00
        private const val MALAYALAM_UNICODE_END = 0x0D7F
        private const val NO_CLEAR_SPEECH_MESSAGE = "No clear speech detected."
        private const val PROVIDE_AUDIO_FILE_MESSAGE = "provide audio file"
    }
}
