# Voice Assistant — Offline-First Android App

A production-ready, offline voice assistant for Android built with:
- **Vosk** — on-device Speech-to-Text
- **MediaPipe LLM Inference (Gemma)** — on-device intent parsing
- **Kotlin Coroutines** — all heavy work off the main thread
- **Foreground Service** — Android 12–14+ compliant

---

## Project Structure

```
app/src/main/
├── java/com/example/voiceassistant/
│   ├── MainActivity.kt           ← UI, permissions, service binding
│   ├── VoiceAssistantService.kt  ← Foreground service, pipeline orchestrator
│   ├── VoiceRecognizerManager.kt ← Vosk STT + AudioRecord
│   ├── LocalModelExecutor.kt     ← Gemma / MediaPipe LLM inference
│   ├── ActionDispatcher.kt       ← JSON command → Android action
│   ├── NotificationHelper.kt     ← Notification channel + builder
│   └── ConversationAdapter.kt    ← RecyclerView chat adapter
├── res/
│   ├── layout/
│   │   ├── activity_main.xml
│   │   ├── item_message_user.xml
│   │   ├── item_message_assistant.xml
│   │   └── item_message_system.xml
│   ├── drawable/
│   │   ├── mic_button_background.xml
│   │   ├── bubble_user.xml
│   │   └── bubble_assistant.xml
│   ├── anim/
│   │   └── pulse.xml
│   └── values/
│       ├── strings.xml
│       ├── colors.xml
│       └── themes.xml
└── AndroidManifest.xml
```

---

## Step 1 — Install the Vosk Model

1. Download a small English model from https://alphacephei.com/vosk/models
   Recommended: **vosk-model-small-en-us-0.15** (~40 MB)
2. Unzip it so you have a folder named `vosk-model-small-en-us-0.15/`
3. Push it to the device **at first launch** or via ADB:

```bash
adb push vosk-model-small-en-us-0.15 \
  /data/data/com.example.voiceassistant/files/models/vosk-model
```

The app expects the model at:
```
/data/data/<package>/files/models/vosk-model/
```
which maps to `context.filesDir + "/models/vosk-model"` in code.

**Alternative (first-run copy from assets):**
Bundle the zipped model under `app/src/main/assets/vosk-model.zip` and
unzip it on first launch using `StorageService.unpack()` from the Vosk API.
See the Vosk Android sample for the exact snippet.

---

## Step 2 — Install the LLM (Gemma)

MediaPipe supports the following model formats on Android:
- Gemma 2B (CPU int8 quantised) — `.task` or `.bin` file (~1.3 GB)
- Gemma 1.1 2B IT — similar size
- Phi-2 (smaller, ~1.5 GB)

### Download
From Google Kaggle: https://www.kaggle.com/models/google/gemma/frameworks/tfLite

Choose: **Gemma 2B IT — CPU int8** → download `.task` file

### Copy to device
```bash
adb push gemma-2b-it-cpu-int8.bin \
  /data/data/com.example.voiceassistant/files/models/gemma-2b-it-cpu-int8.bin
```

The app expects the model at:
```
/data/data/<package>/files/models/gemma-2b-it-cpu-int8.bin
```
Set `LocalModelExecutor.MODEL_FILE_NAME` if you use a different filename.

### Memory requirements
| Model             | RAM needed | Notes                            |
|-------------------|------------|----------------------------------|
| Gemma 2B int8     | ~2.5 GB    | Works on 4 GB RAM devices        |
| Phi-2 Q4_K_M GGUF | ~1.5 GB    | Requires llama.cpp JNI (swap in) |
| Gemma 2 1B IT     | ~1.2 GB    | Best for constrained hardware    |

---

## Step 3 — Build & Run

```bash
# Sync dependencies
./gradlew dependencies

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

Or open in **Android Studio Hedgehog (2023.1.1+)** and click ▶ Run.

---

## Supported Voice Commands (Default)

| You say                        | Action dispatched            |
|-------------------------------|------------------------------|
| "Open YouTube"                 | Launch YouTube app            |
| "Open Chrome"                  | Launch Chrome browser         |
| "Battery level"                | Reads battery % + status      |
| "Turn on Wi-Fi"                | Enables Wi-Fi (API ≤ 28)      |
| "Open Wi-Fi settings"          | Opens Settings panel          |
| "Open Bluetooth settings"      | Opens Bluetooth settings      |
| "What's the weather?" (etc.)   | SPEAK reply from the LLM      |

To add more actions, extend `ActionDispatcher.routeAction()` and add matching
entries to the `SYSTEM_PROMPT` in `LocalModelExecutor`.

---

## Architecture Overview

```
[User taps mic]
       │
       ▼
  MainActivity  ──bind──▶  VoiceAssistantService
                                  │
                     ┌────────────┴──────────────┐
                     ▼                           ▼
          VoiceRecognizerManager          LocalModelExecutor
          (AudioRecord + Vosk STT)        (MediaPipe / Gemma)
                     │                           │
                     │    transcript             │  JSON intent
                     └──────────────────────────▶│
                                                 ▼
                                        ActionDispatcher
                                     (parse JSON → Android)
                                                 │
                                    ┌────────────┴────────────┐
                                    ▼                         ▼
                             startActivity()           TextResponse
                          (open app / settings)      TTS + chat log
```

---

## Initialization Order (Critical)

The service enforces strict init order to prevent use-before-ready crashes:

```
onCreate()  →  NotificationHelper.createChannel()  →  acquire WakeLock
onStartCommand()  →  startForeground() ← MUST be within 5 s on API 34
initModels() [coroutine, IO thread]:
   1. voiceRecognizer.init()   ← loads Vosk model
   2. modelExecutor.init()     ← loads Gemma (slow: 1–5 s)
   3. TTS init (main thread)
   4. setState(IDLE)           ← only NOW can startListening() be called
```

---

## Troubleshooting

| Symptom                            | Fix                                                |
|------------------------------------|----------------------------------------------------|
| App crashes on start               | Check that both model files are in place           |
| "STT model not found" toast        | Push vosk-model folder via ADB (Step 1)            |
| "Model load error" toast           | Push Gemma .bin via ADB (Step 2); check free space |
| Mic button stays grey              | Grant RECORD_AUDIO permission in Settings          |
| Service killed immediately         | Check foregroundServiceType in manifest            |
| ANR on main thread                 | Never call voiceRecognizer / modelExecutor directly from UI |
| Wi-Fi toggle does nothing (API 29+)| By design: opens Settings panel instead (OS restriction) |

---

## Replacing MediaPipe with llama.cpp (Optional)

If you prefer a GGUF model via llama.cpp:

1. Add the `llama-android` JNI wrapper dependency to `build.gradle`
2. In `LocalModelExecutor`, replace the `LlmInference` calls with:
   ```kotlin
   val llama = LlamaContext.create(modelPath, nThreads = 4)
   val output = llama.completion(prompt, maxTokens = MAX_TOKENS)
   ```
3. Adjust `buildPrompt()` to use ChatML format:
   ```
   <|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant
   ```
Everything else (ActionDispatcher, Service, STT) remains unchanged.

---

## License
MIT — free to use, modify, and distribute.
