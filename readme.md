# Omu Translate: Real-Time Malayalam-English Edge Translation

A high-performance, native Android application designed for **simultaneous, phrase-by-phrase translation** from Malayalam to English. This project leverages on-device Large Language Models (LLMs) and Neural Processing Units (NPUs) to achieve sub-1-second latency without an internet connection.

---

## Key Features

*   **Sub-1s Latency:** Optimized for the **Exynos 2400 NPU** to ensure translations appear almost instantly after a user finishes speaking.
*   **Multimodal Audio Ingestion:** Utilizes **Gemma 4 E2B**'s native audio capabilities, bypassing traditional, slow Speech-to-Text (STT) pipelines.
*   **Intelligent VAD:** Implements **Silero VAD** via ONNX Runtime to detect natural speech pauses and trigger translation contextually.
*   **100% Offline:** All processing happens on-device, ensuring total privacy and functionality in zero-connectivity environments.
*   **Human-Centric Translation:** Uses LLM reasoning to handle Malayalam’s SOV (Subject-Object-Verb) structure, providing natural English output rather than robotic word-for-word mapping.

---

## ️ Architecture

The project follows a **Unidirectional Data Flow** within an **MVVM** architecture to ensure the UI remains responsive during heavy NPU inference.

| Component | Responsibility | Tech Stack |
| :--- | :--- | :--- |
| **MicRecorder** | Captures raw 16kHz mono PCM audio frames. | `AudioRecord` API |
| **VadEngine** | Detects speech/silence in 32ms windows. | `Silero VAD` + `ONNX Runtime` |
| **TranslationStream** | Manages phrase buffers and the 400ms silence threshold. | `Kotlin Coroutines` |
| **GemmaEngine** | Executes direct Audio-to-English translation. | `Gemma 4 E2B` + `LiteRT-LM` |
| **UI Layer** | Displays streaming results in real-time. | `Jetpack Compose` |

---

## Tech Stack

*   **Language:** Kotlin 2.1.x
*   **UI:** Jetpack Compose
*   **AI Inference:**
    *   **LiteRT-LM** (Google AI Edge SDK) for Gemma 4.
    *   **ONNX Runtime** for Silero VAD.
*   **Hardware Acceleration:** Android NNAPI / Samsung Exynos NPU Delegate.

---

## Project Structure

```text
app/src/main/
├── assets/                     # Model weights (.onnx, .litertlm)
├── java/com/Omu/translator/
│   ├── audio/                  # Audio capture & byte-stream logic
│   ├── ml/                     # AI Engine wrappers (VAD, Gemma)
│   ├── pipeline/               # The VAD State Machine & Orchestrator
│   └── ui/                     # Jetpack Compose Screens & ViewModels
└── AndroidManifest.xml         # Hardware & Microphone permissions
```

---

## Setup & Installation

### Prerequisites
*   **Hardware:** Android Device.
*   **Models:**
    1. Place `silero_vad.onnx` in `app/src/main/assets/`.
    2. Place `gemma-4-e2b-audio.litertlm` in the app's internal data directory (or side-load for testing).

### Permissions
The app requires the following permission in your `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

---

## Performance Goals

| Metric | Target | Status |
| :--- | :--- | :--- |
| **Inference Latency** | < 800ms | 🟢 Optimized |
| **Memory Footprint** | ~2.5GB RAM | 🟢 Controlled |
| **Power Efficiency** | GPU-Offloaded | 🟢 Optimized |

---

## ⚖️ License

Distributed under the MIT License. See `LICENSE` for more information.

---
