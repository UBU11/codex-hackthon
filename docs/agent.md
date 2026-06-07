# Agent Implementation Guide: On-Device SuperTonic 3 TTS Pipeline

This is the TTS-specific implementation guide for Omu. The repository-wide
instructions in `AGENTS.md` remain authoritative for package names, build
commands, and general conventions.

## Objective

Maintain the on-device Text-to-Speech path that accepts English text produced by
`GemmaEngine`, synthesizes speech with SuperTonic 3 ONNX assets, and streams the
result through native Android audio hardware.

Critical constraints:

- Use native Kotlin and Android APIs only.
- Keep the pipeline offline-first; do not add network TTS.
- Prevent feedback loops: the microphone must drop frames while generated speech
  is playing.
- Close ONNX sessions/tensors and native audio resources on lifecycle cleanup.
- Minimize allocations in audio and inference hot paths.

## Actual Repository Layout

Use the existing `com.example.omu` package:

```text
app/src/main/
+-- assets/
|   +-- silero_vad.onnx
|   +-- android_tts_assets/
|       +-- onnx/
|       |   +-- tts.json
|       |   +-- unicode_indexer.json
|       |   +-- duration_predictor.onnx
|       |   +-- text_encoder.onnx
|       |   +-- vector_estimator.onnx
|       |   +-- vocoder.onnx
|       +-- voice_styles/
|           +-- F1.json
+-- java/com/example/omu/
    +-- audio/
    |   +-- AudioSpeaker.kt
    |   +-- MicRecorder.kt
    +-- ml/
    |   +-- GemmaEngine.kt
    |   +-- TtsEngine.kt
    |   +-- VadEngine.kt
    +-- pipeline/
    |   +-- TurnStateMachine.kt
    +-- ui/
        +-- MainViewModel.kt
        +-- TranslateScreen.kt
```

Do not create `app/src/main/java/com/zecure/translator`. That path is stale and
will not match the current Gradle namespace.

## AudioSpeaker Contract

`audio/AudioSpeaker.kt` is the only playback path for synthesized speech.

- Use `android.media.AudioTrack`.
- Do not use `MediaPlayer` or `ExoPlayer` for generated TTS playback.
- Output format is mono PCM16 through `AudioTrack.MODE_STREAM`.
- SuperTonic output is normalized float PCM in `[-1.0, 1.0]`; convert it to
  signed 16-bit PCM before writing:

```text
pcm16 = clamp(floor(sample * 32767.0), -32768, 32767)
```

- `playRawFloats(samples: FloatArray)` must be thread-safe and blocking until
  playback completes or `stop()` is requested.
- Stop/cleanup paths must pause, stop or flush as appropriate, and release the
  `AudioTrack` in `close()`.

## TtsEngine Contract

`ml/TtsEngine.kt` uses the split SuperTonic asset layout, not a single
`supertonic_3.onnx` file.

Required assets:

- `onnx/tts.json`
- `onnx/unicode_indexer.json`
- `onnx/duration_predictor.onnx`
- `onnx/text_encoder.onnx`
- `onnx/vector_estimator.onnx`
- `onnx/vocoder.onnx`
- `voice_styles/<voiceName>.json`

Current behavior to preserve:

- Default voice is `F1`.
- Default language is English (`en`).
- Flow-matching generation uses exactly 5 steps.
- Long text is chunked before synthesis.
- ONNX Runtime sessions use CPU/XNNPACK settings where available.
- Asset layouts may be staged into app files because ONNX Runtime sessions need
  file paths for model files.

Text preprocessing must use `unicode_indexer.json` to convert Unicode code
points into SuperTonic text IDs, build a text mask, and bind the selected voice
style tensors.

## Feedback Prevention

Generated speech must not be fed back into the microphone/VAD pipeline.

The current safe flow is:

1. `GemmaEngine` emits final English text.
2. `MainViewModel.speakTranslation()` sets `systemSpeaking` to true.
3. UI state moves to `TurnState.SYSTEM_SPEAKING`.
4. `TurnStateMachine` sees `isSystemSpeaking()`, clears buffered audio, resets
   VAD state, and drops incoming mic frames.
5. `TtsEngine.synthesize()` returns a `FloatArray`.
6. `AudioSpeaker.playRawFloats()` plays the stream to completion.
7. `systemSpeaking` is reset and state returns to `TurnState.IDLE`.

Do not rename the state to `STATE_SYSTEM_SPEAKING`; the actual enum value is
`TurnState.SYSTEM_SPEAKING`.

## Verification Checklist

For TTS-related code changes, verify as much as the environment allows:

- `./gradlew :app:assembleDebug`
- Confirm `AudioSpeaker.stop()` interrupts playback cleanly.
- Confirm `MainViewModel.stopListening()` clears `systemSpeaking`, stops TTS,
  and resets the pipeline state.
- Confirm missing or invalid SuperTonic assets produce a visible error instead
  of a silent failure.
