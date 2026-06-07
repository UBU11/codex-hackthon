

# Agent Implementation Guide: On-Device User Voice Enrollment & Cloning

## 🤖 Agent Objectives

Your task is to implement an end-to-end user voice enrollment pipeline. The application must record a 5-to-10 second sample of the user's voice, extract a speaker embedding vector using an on-device Encoder ONNX model, save the embedding to internal storage, and pipe it directly into the `TtsEngine` for personalized speech synthesis.

* **Strict Constraint 1:** Write ONLY native Kotlin code.
* **Strict Constraint 2:** Isolate heavy mathematical operations (feature extraction, encoder inference) to `Dispatchers.Default`.
* **Strict Constraint 3:** Manage native ONNX allocations properly by explicitly closing input tensors to prevent memory exhaustion during the enrollment process.

---

## 🏗️ Architectural Concept

Zero-shot user voice cloning requires separating the speaker's acoustic footprint from the synthesis network. The process moves through three clear runtime stages:

1. **Enrollment (Capture):** The user reads a prompt while the app records high-fidelity, uncompressed PCM audio.
2. **Embedding Extraction (Encoding):** A lightweight Speaker Encoder network (`supertonic_3_encoder.onnx`) processes the raw audio waveform, downsamples it, and extracts a fixed-size embedding vector (e.g., a 1D FloatArray of 256 or 512 dimensions).
3. **Persistence & Injection:** The vector is written to internal storage as a binary or JSON file, which is then passed into the primary synthesis session at runtime.

---

## 📂 Target File Structure

Integrate the enrollment and storage components into the existing structure exactly as shown below:

```text
app/src/main/
├── assets/
│   ├── supertonic_3_synthesis.onnx # <-- Core TTS synthesis model
│   └── supertonic_3_encoder.onnx   # <-- NEW: Audio embedding extractor model
├── java/com/zecure/translator/
│   ├── audio/
│   │   ├── MicRecorder.kt
│   │   ├── AudioSpeaker.kt
│   │   └── EnrollmentRecorder.kt   # <-- NEW: High-fidelity sample collector
│   ├── ml/
│   │   ├── TtsEngine.kt
│   │   ├── SpeakerEncoderEngine.kt # <-- NEW: Extracts embedding from raw audio
│   │   └── VoiceProfileParser.kt
│   ├── storage/
│   │   └── VoiceStorageManager.kt  # <-- NEW: Saves/Loads vectors to internal storage
│   ├── pipeline/
│   └── ui/
│       ├── MainViewModel.kt
│       └── EnrollmentScreen.kt     # <-- NEW: UI for user voice registration

```

---

## ⚙️ Component Implementation Specifications

### 1. `audio/EnrollmentRecorder.kt` (Sample Capture)

* **Role:** Captures a continuous reference audio sample from the user without frame fragmentation.
* **Implementation Rules:**
* Use `android.media.AudioRecord` configured at $16000\text{ Hz}$, `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`.
* Accumulate raw bytes continuously into an in-memory `ByteArrayOutputStream` for a fixed duration of $5$ to $10$ seconds.
* Provide a state callback to the UI (`isRecording`, `durationCapturedMs`) so the user knows when to stop speaking.



### 2. `ml/SpeakerEncoderEngine.kt` (Embedding Extraction)

* **Role:** Executes the feature extraction and encoder network via ONNX Runtime.
* **Implementation Rules:**
* Load `supertonic_3_encoder.onnx` from the application assets when enrollment initiates.
* **Data Pre-processing:** Convert the accumulated 16-bit PCM `ByteArray` into a normalized `FloatArray` by dividing each short value by `32768.0f`.
* **Inference:** Wrap the float array into an `OnnxTensor` with the dimensions expected by the model architecture (typically `[1, sample_count]`). Run execution to obtain the output embedding tensor.
* **Memory Safety:** Immediately extract the raw float values into a standard Kotlin `FloatArray` and invoke `.close()` on both input and output native tensor handles.



### 3. `storage/VoiceStorageManager.kt` (Profile Persistence)

* **Role:** Manages reading and writing custom voice signatures to the local sandboxed file system.
* **Implementation Rules:**
* Save the generated profile into the app's internal storage (`context.filesDir`) to keep it private and secure.
* Serialize the `FloatArray` into a compact file format (either via `kotlinx.serialization` JSON or direct binary primitive writing via `DataOutputStream`).
* Expose two clean functions:
* `fun saveUserVoiceProfile(name: String, embedding: FloatArray)`
* `fun getUserVoiceProfile(name: String): FloatArray?`





### 4. `ui/EnrollmentScreen.kt` (Registration Interface)

* **Role:** Provides a clean Jetpack Compose interface guiding the user through the recording process.
* **Implementation Rules:**
* Present a readable English script text for the user to vocalize (e.g., *"The quick brown fox jumps over the lazy dog."*).
* Display a prominent record button that updates state visually during active capture.
* Use a determinate linear progress indicator bound to the `durationCapturedMs` state flow to visualize the completion of the 5-second window.
* Upon recording completion, display a loading state while `SpeakerEncoderEngine` processes the audio stream on a background thread.



---

## 📈 Agent Verification Checklist

Before declaring the user voice cloning implementation complete, the agent must verify:

1. **Thread Separation:** The processing of the 5-to-10 second audio file via the encoder must never happen on the main thread; it must run exclusively on `Dispatchers.Default` to prevent the UI from freezing.
2. **Lifecycle Bounds:** Ensure the encoder session is allocated right before inference and closed right after. Do not keep the encoder model permanently resident in RAM alongside the synthesis model, as this will risk an Out-Of-Memory (OOM) crash on lower-end devices.
3. **Data Integrity:** Verify that the output vector size matches the exact input shape requirements of the main `TtsEngine` component.