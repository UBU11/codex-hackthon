

---

# Agent Implementation Guide: Supertonic 3 Voice Cloning

## 🤖 Agent Objectives

Your task is to implement the dynamic voice cloning feature for the on-device Supertonic 3 TTS pipeline. You must update the `TtsEngine` to accept and parse custom cloned voice embeddings (JSON/Tensor formats) and apply them to the inference session.

* **Strict Constraint 1:** Write ONLY native Kotlin code.
* **Strict Constraint 2:** Ensure zero memory leaks during tensor initialization. The embedding float arrays must be carefully managed to avoid Garbage Collection (GC) pauses on the `Dispatchers.Default` thread.
* **Strict Constraint 3:** Implement a dynamic swapping mechanism so the user can change their cloned voice profile at runtime without restarting the ONNX environment.

---

## 🏗️ Architectural Concept

Supertonic 3 achieves voice cloning through a flow-matching architecture that separates text semantics from acoustic identity.

* A cloned voice is simply a mathematical representation of a speaker's acoustic identity, stored as a multi-dimensional Float array (a `voice_style` embedding).
* Instead of running a heavy speaker-encoder on the phone every time the user speaks, the app will parse a pre-computed `custom_voice.json` (or `.tensor`) file and inject it into the ONNX session alongside the text tokens.

---

## 📂 Target File Structure

Update the existing project structure to accommodate dynamic voice profiles:

```text
app/src/main/
├── assets/
│   ├── supertonic_3.onnx
│   ├── default_preset.json     # <-- Fallback voice
│   └── voices/                 # <-- NEW: Directory for custom cloned voices
│       ├── user_clone_1.json
│       └── user_clone_2.json
├── java/com/zecure/translator/
│   ├── audio/
│   ├── ml/
│   │   ├── TtsEngine.kt        # <-- MODIFIED: Dynamic embedding loader
│   │   └── VoiceProfileParser.kt # <-- NEW: Deserializes JSON into FloatArrays
│   ├── pipeline/
│   └── ui/
│       ├── MainViewModel.kt    # <-- MODIFIED: Voice selection state
│       └── SettingsScreen.kt   # <-- NEW: UI to swap cloned voices

```

---

## ⚙️ Component Implementation Specifications

### 1. `ml/VoiceProfileParser.kt` (The Embedding Loader)

* **Role:** Reads custom cloned voice JSON files and converts them into flat `FloatArray` primitives that ONNX Runtime can ingest.
* **Implementation Rules:**
* Use `kotlinx.serialization.json` to parse the `custom_voice.json` files natively.
* The JSON structure will contain the embedding matrix. You must flatten this multi-dimensional array into a single 1D `FloatArray`.
* Expose a function: `suspend fun loadVoiceEmbedding(fileName: String): FloatArray`.
* Execute file I/O strictly on `Dispatchers.IO`.



### 2. `ml/TtsEngine.kt` (Dynamic Tensor Injection)

* **Role:** Modifies the existing TTS ONNX inference loop to accept the parsed custom embedding.
* **Implementation Rules:**
* Remove any hardcoded references to preset voice tensors.
* Update the `synthesize(text: String, voiceStyleArray: FloatArray)` function signature.
* **ONNX Tensor Binding:** Before calling `session.run()`, construct the `voice_style` tensor dynamically from the provided `FloatArray`:
```kotlin
// Example target logic for the Agent:
val shape = longArrayOf(1, EMBEDDING_DIMENSION_SIZE) // Dimension depends on Supertonic 3 specs
val styleBuffer = FloatBuffer.wrap(voiceStyleArray)
val styleTensor = OnnxTensor.createTensor(env, styleBuffer, shape)

```


* **Memory Safety:** The `styleTensor` MUST be explicitly closed (`styleTensor.close()`) in a `finally` block after inference to prevent native memory leaks in the C++ layer.



### 3. `ui/MainViewModel.kt` (State Management)

* **Role:** Manages which cloned voice is currently active.
* **Implementation Rules:**
* Expose a `MutableStateFlow<FloatArray>` that holds the currently selected voice embedding in memory.
* When the application starts, load a default cloned voice into this flow.
* When the `TurnStateMachine` requests a translation synthesis, pass the current state of this flow directly into `TtsEngine.synthesize()`.



### 4. `ui/SettingsScreen.kt` (User Interface)

* **Role:** Allows the user to select between different cloned voice profiles loaded in the `assets/voices/` directory.
* **Implementation Rules:**
* Build a simple Jetpack Compose `DropdownMenu` or `LazyColumn` listing the available cloned voices.
* Upon selection, trigger the `VoiceProfileParser` via the ViewModel to update the active `FloatArray` in memory.



---

## 📈 Agent Verification Checklist

Before declaring the voice cloning implementation complete, the agent must verify:

1. **Dynamic Swapping:** Ensure that changing the voice profile in the UI does *not* require re-instantiating the entire `OrtSession` (ONNX session), which is an expensive operation. Only the `styleTensor` should change per inference.
2. **Buffer Alignment:** Verify that the `FloatBuffer` wrapper matches the exact dimensional shape expected by the `supertonic_3.onnx` input layer.
3. **No String Over-allocation:** Ensure JSON parsing maps directly to primitives without creating massive intermediate String maps in memory.
