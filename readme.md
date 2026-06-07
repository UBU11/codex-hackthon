# Omu: On-Device Multimodal Malayalam-to-English Translator

## Overview
Omu is a high-performance, privacy-focused Android application designed for real-time, on-device translation of spoken Malayalam into natural English. By leveraging state-of-the-art multimodal Large Language Models (LLMs) and local Text-to-Speech (TTS) engines, Omu provides a seamless "hear-and-speak" experience without requiring an internet connection.

## Problem Statement
Real-time translation for regional languages like Malayalam often suffers from high latency, reliance on cloud services (raising privacy concerns), and poor handling of natural, spoken dialect. Most existing solutions require an active data connection, making them unreliable in areas with poor connectivity.

## Solution
Omu solves these challenges by running the entire translation pipeline locally on the Android device:
1.  **Voice Activity Detection (VAD):** Silently monitors for speech using Silero VAD.
2.  **Multimodal Translation:** Uses **Gemma 4 E2B** to translate raw audio PCM directly into English text.
3.  **Natural TTS:** Synthesizes the translation back into high-quality speech using the **SuperTonic 3** engine with customized voice profiles.

## Features
- **Zero-Latency Privacy:** All processing happens on-device; no audio ever leaves the phone.
- **Multimodal Understanding:** Gemma 4 processes audio features directly for better context than traditional ASR+MT pipelines.
- **Custom Voice Enrollment:** Users can record their own voice to create a unique TTS profile that sounds like them.
- **Feedback Prevention:** Intelligent state management ensures the system doesn't "hear itself" during playback.
- **Robust VAD:** Highly optimized Silero VAD prevents accidental triggers from background noise.

## Tech Stack
- **Frontend:** Jetpack Compose (Kotlin)
- **ML Runtime:** ONNX Runtime & LiteRT (TensorFlow Lite)
- **Models:** Gemma 4 E2B (Multimodal LLM), SuperTonic 3 (TTS), Silero VAD
- **Architecture:** MVVM with Kotlin Coroutines and Flows
- **Audio:** Android AudioRecord (Input) and AudioTrack (Output)

## Model Loading Instructions
Omu requires specific model assets to be loaded onto the device's external storage to function.

### 1. Gemma 4 E2B (Translation)
- **Model Format:** `.litertlm` (LiteRT-LM bundle)
- **Required File:** `gemma-4-e2b-audio.litertlm` (or similar naming pattern)
- **Location:** Place the file in your device's app-specific directory:
  `/Android/data/com.example.omu/files/`
- **Verification:** The app will log "Using Gemma model: ..." on startup if found.

### 2. SuperTonic 3 (TTS)
- **Required Assets:**
  - `onnx/tts.json`, `onnx/unicode_indexer.json`
  - `onnx/duration_predictor.onnx`, `onnx/text_encoder.onnx`, `onnx/vector_estimator.onnx`, `onnx/vocoder.onnx`
  - `voice_styles/F1.json` (and other style files)
- **Loading:** These assets are typically bundled in the `assets/android_tts_assets/` folder of the project. If you are updating them manually, ensure the directory structure matches:
  `/Android/data/com.example.omu/files/android_tts_assets/onnx/`

### 3. Silero VAD
- **File:** `silero_vad.onnx`
- **Location:** Bundled in the app's `assets/` folder and loaded automatically at runtime.

## Screenshots
*(Add screenshots here showing the translation history and voice enrollment screens)*

## How to Run Locally

1.  **Clone the Repo:**
    ```bash
    git clone https://github.com/UBU11/codex-hackthon
    cd Omu
    ```
2.  **Open in Android Studio:**
    - Import the project and let Gradle sync.
3.  **Add Model Assets:**
    - Follow the **Model Loading Instructions** above to place your Gemma `.litertlm` file on the device/emulator.
4.  **Build and Run:**
    - Connect an Android device (Target SDK 36 recommended).
    - Run the `:app` module.
    - Grant **Record Audio** permissions when prompted.
