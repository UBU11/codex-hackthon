# AGENTS.md

Guidance for coding agents working in this repository.

## Project Overview

Omu is a native Android app for offline, low-latency Malayalam-to-English speech translation. The app captures 16 kHz mono PCM audio, detects speech turns with Silero VAD through ONNX Runtime, sends completed turns to Gemma through LiteRT-LM, then speaks English translations through the on-device SuperTonic 3 TTS pipeline.

The main runtime path is:

```text
MicRecorder -> TurnStateMachine -> GemmaEngine -> TtsEngine -> AudioSpeaker
```

Keep this pipeline offline-first. Do not add network translation, network TTS, or cloud model dependencies unless the user explicitly asks for that architectural change.

## Repository Layout

```text
app/src/main/java/com/example/omu/
+-- MainActivity.kt              # Compose entry point and mic permission flow
+-- audio/                       # AudioRecord, AudioTrack, phrase buffering
+-- core/                        # Permissions and shared timing/audio constants
+-- ml/                          # Gemma, Silero VAD, and SuperTonic wrappers
+-- pipeline/                    # Turn state machine and translation stream alias
+-- ui/                          # ViewModel, Compose screen, theme

app/src/main/assets/
+-- silero_vad.onnx
+-- android_tts_assets/          # SuperTonic ONNX files, config, indexer, voices
```

The package namespace is `com.example.omu`. Do not use stale package names from older docs when adding files.

## Build And Test Commands

Use the repo Gradle wrapper:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:lintDebug
```

`connectedDebugAndroidTest` requires a connected Android device or emulator. Model-heavy runtime behavior generally needs a physical device with the required assets installed.

## Runtime Assets

- `silero_vad.onnx` is bundled from `app/src/main/assets/silero_vad.onnx`.
- SuperTonic 3 assets are expected in `app/src/main/assets/android_tts_assets/` with:
  - `onnx/tts.json`
  - `onnx/unicode_indexer.json`
  - `onnx/duration_predictor.onnx`
  - `onnx/text_encoder.onnx`
  - `onnx/vector_estimator.onnx`
  - `onnx/vocoder.onnx`
  - `voice_styles/*.json`
- Gemma `.litertlm` files are discovered at runtime from the app external files directory. `GemmaEngine` currently checks names such as `gemma-4-e2b-audio.litertlm`, `gemma-4-E2B-it.litertlm`, and `gemma-4-e2b-it.litertlm`, then falls back to any non-empty `.litertlm` file whose name contains `gemma`.
- Do not commit generated runtime files such as `last_turn.wav`.

## Audio And Pipeline Contracts

- Microphone frames are 16 kHz, mono, PCM16, 32 ms windows. Keep changes aligned with `AppConstants.FRAME_BYTES`, `FRAME_SAMPLES`, and `FRAME_SIZE_MS`.
- Centralize VAD thresholds, turn duration limits, pre-roll, and cooldown tuning in `AppConstants`.
- Preserve the feedback-prevention path:
  - `MainViewModel.speakTranslation()` sets `systemSpeaking` before TTS playback.
  - `TurnStateMachine` enters `TurnState.SYSTEM_SPEAKING`, clears buffered speech, resets VAD state, and drops mic frames while the app is speaking.
  - The state returns to `IDLE` after playback finishes or is stopped.
- `AudioSpeaker` uses `AudioTrack.MODE_STREAM` and converts normalized floats to signed 16-bit PCM. Avoid `MediaPlayer` or `ExoPlayer` for generated speech playback.

## Kotlin And Architecture Conventions

- Kotlin style is official (`kotlin.code.style=official`), with Java 11 compatibility.
- Keep UI state in `MainViewModel` as `StateFlow<TranslateUiState>`. Keep `TranslateScreen` mostly stateless and callback-driven.
- Use coroutines consistently:
  - `Dispatchers.IO` for microphone capture, file access, and model initialization.
  - `Dispatchers.Default` for VAD/TTS CPU-heavy work.
  - Update UI state through `_uiState.update { ... }`.
- Close native/model resources in lifecycle cleanup paths (`onCleared`, `close`, `stop`). ONNX tensors/sessions and LiteRT conversations must be closed when no longer needed.
- Prefer primitive arrays/direct buffers in audio and inference loops. Minimize allocation in hot paths.
- Keep model wrapper failures explicit in logs and user-facing `error` state. Do not silently swallow initialization failures that affect the UI.

## Dependency Notes

Dependencies are managed through `gradle/libs.versions.toml`. This app uses Jetpack Compose Material 3, Kotlin coroutines, LiteRT-LM, and ONNX Runtime Android. Prefer updating the version catalog over hardcoding dependency coordinates in module build files.

## Existing Docs

- `readme.md` describes the product goal and high-level architecture.
- `docs/agent.md` is the task-specific implementation brief for the SuperTonic TTS path.
- `docs/vad.md` is the task-specific implementation brief for the microphone, VAD, and Gemma translation path.
- `my-project/AGENTS.md` is a small pointer file and does not define the Android app conventions.

## Before Finishing Changes

For code changes, run the narrowest relevant Gradle verification command and report whether it passed. If a command cannot run because a device, model file, or network access is unavailable, state that explicitly.
