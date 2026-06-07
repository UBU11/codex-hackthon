# Agent Implementation Guide: Omu Native Android VAD And Translation Pipeline

This document covers the microphone, VAD, turn detection, and Gemma translation
path. Use `AGENTS.md` for repository-wide conventions.

## Directives

- Write native Kotlin only.
- Use Jetpack Compose for UI; do not add XML layouts.
- Preserve MVVM and unidirectional data flow with Kotlin Coroutines and Flows.
- Keep the app offline-first. Do not introduce network translation.
- Minimize allocations inside continuous audio/VAD loops.

## Technical Specifications

- Gradle project: single `:app` Android module.
- Package namespace: `com.example.omu`.
- Min SDK: 24.
- Target SDK: 36.
- Audio input: 16 kHz, mono, PCM16.
- Frame size: 32 ms, 512 samples, 1024 bytes.
- Core constants live in `app/src/main/java/com/example/omu/core/AppConstants.kt`.

Dependencies are managed in `gradle/libs.versions.toml`; do not hardcode new
coordinates in `app/build.gradle.kts` unless the version catalog is also updated.
Important libraries already present:

- `com.microsoft.onnxruntime:onnxruntime-android`
- `com.google.ai.edge.litertlm:litertlm-android`
- AndroidX Compose Material 3
- Kotlin coroutines Android

## Actual File Structure

Generated code must stay under the existing package:

```text
app/src/main/
+-- assets/
|   +-- silero_vad.onnx
|   +-- android_tts_assets/
+-- java/com/example/omu/
    +-- MainActivity.kt
    +-- audio/
    |   +-- MicRecorder.kt
    |   +-- PhraseBuffer.kt
    |   +-- AudioSpeaker.kt
    +-- core/
    |   +-- AppConstants.kt
    |   +-- Permissions.kt
    +-- ml/
    |   +-- VadEngine.kt
    |   +-- GemmaEngine.kt
    |   +-- TtsEngine.kt
    +-- pipeline/
    |   +-- TurnStateMachine.kt
    |   +-- TranslationStream.kt
    +-- ui/
        +-- MainViewModel.kt
        +-- TranslateScreen.kt
```

Do not create `app/src/main/java/com/zecure/translator`; that package is stale.

## Module Rules

### `audio/MicRecorder.kt`

- Wrap Android `AudioRecord` in `Flow<ByteArray>`.
- Run capture on `Dispatchers.IO`.
- Emit full frames of exactly `AppConstants.FRAME_BYTES`.
- Handle missing or revoked microphone permission via `SecurityException`.
- Release `AudioRecord` in `finally`.

### `ml/VadEngine.kt`

- Load `silero_vad.onnx` from assets.
- Convert PCM16 little-endian bytes to normalized floats with
  `sample / 32768.0f`.
- Reuse direct buffers and tensors across calls where possible.
- Preserve recurrent Silero state tensors (`state` or `h`/`c`) when present, and
  reset them between turns or when the system starts speaking.
- Return a confidence score clamped to `0.0f..1.0f`.

### `pipeline/TurnStateMachine.kt`

The current states are:

- `IDLE`
- `SPEAKING`
- `SYSTEM_SPEAKING`
- `TURN_COMPLETE`

Preserve the turn detection behavior:

- Speech start threshold: `AppConstants.SPEECH_START_THRESHOLD`.
- Speech stop threshold: `AppConstants.SPEECH_STOP_THRESHOLD`.
- Silence threshold: `AppConstants.SILENCE_THRESHOLD_MS`.
- Minimum turn/voiced duration and cooldown are enforced through
  `AppConstants`.
- Pre-roll frames are retained so the beginning of speech is not clipped.
- Completed turns emit `TurnEvent.TurnComplete(audioPcm16, durationMs)`.

`SYSTEM_SPEAKING` is critical. While `isSystemSpeaking()` is true, the state
machine must clear buffered audio, reset VAD, and drop microphone frames so TTS
output cannot trigger another translation turn.

### `ml/GemmaEngine.kt`

- Use LiteRT-LM through the current `com.google.ai.edge.litertlm` APIs.
- Gemma model files are discovered from the app external files directory at
  runtime; do not assume the `.litertlm` model is bundled in APK assets.
- Current primary config uses `Backend.GPU()` with `audioBackend = Backend.CPU()`.
- Convert completed PCM16 turns into WAV bytes before attaching them as
  `Content.AudioBytes`.
- Translation prompts must continue to require English-only output.
- Close LiteRT conversations and engine resources.

### `ui/MainViewModel.kt` and `ui/TranslateScreen.kt`

- `MainViewModel` exposes `StateFlow<TranslateUiState>`.
- UI updates must go through `_uiState.update { ... }`.
- Compose UI should remain stateless and callback-driven.
- Do not block the main thread with model initialization, VAD, translation, or
  TTS work.

## Verification

For VAD or translation pipeline changes, run the narrowest relevant check:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Use `./gradlew :app:connectedDebugAndroidTest` only when a device or emulator is
available. Runtime Gemma behavior also requires a valid `.litertlm` file in the
app external files directory.
