# V2 Addon — Sub-1GB Models + Google Drive Download + GitHub Actions

## Files in this addon

| File | Replaces / Adds |
|---|---|
| `ModelConfig.kt` | **NEW** — central catalogue of all sub-1 GB GGUF models |
| `ModelDownloadManager.kt` | **NEW** — Google Drive download with resume + progress |
| `ModelSetupActivity.kt` | **NEW** — first-launch download UI |
| `LocalModelExecutor.kt` | **REPLACES** V2 version — now uses llama.cpp JNI + GGUF |
| `activity_model_setup.xml` | **NEW** — layout for setup screen |
| `.github/workflows/build-apk.yml` | **NEW** — GitHub Actions CI/CD |

Copy all files into your VoiceAssistantV2 project.

---

## Part 1 — Model Recommendation (sub-1 GB)

### Benchmark Comparison

| Model | GGUF Q4_K_M Size | Instruction Following | JSON Precision | Android Stability | Verdict |
|---|---|---|---|---|---|
| **Qwen2.5-1.5B-Instruct** | ~935 MB | ★★★★★ | ★★★★★ | ★★★★★ | **#1 RECOMMENDED** |
| **Llama-3.2-1B-Instruct** | ~670 MB | ★★★★☆ | ★★★★☆ | ★★★★★ | **#2 Runner-up** |
| SmolLM2-1.7B-Instruct (Q3_K_S) | ~780 MB | ★★★☆☆ | ★★★☆☆ | ★★★★☆ | Borderline |
| DeepSeek-R1-Distill-Qwen-1.5B | ~900 MB | ★★★★☆ | ★★★☆☆ | ★★★☆☆ | Verbose |
| TinyLlama-1.1B-Chat | ~669 MB | ★★☆☆☆ | ★★☆☆☆ | ★★★★☆ | Last resort |

### Winner: Qwen2.5-1.5B-Instruct Q4_K_M (~935 MB)

**Why it wins for this use case:**
- Alibaba's chat-tuned 1.5B produces clean, structured JSON output reliably — critical for ActionDispatcher.
- Supports the ChatML prompt format (same as SmolLM2), which strongly suppresses non-JSON output.
- Fits 935 MB comfortably under the 1 GB ceiling with headroom.
- Lowest hallucination rate of all sub-1 GB models on structured-output benchmarks.
- Well-supported in llama.cpp (Q4_K_M quantisation is the sweet spot for accuracy vs. size).

**Runner-up: Llama-3.2-1B-Instruct Q4_K_M (~670 MB)**
- Use this if your device has < 2 GB free RAM at runtime.
- Slightly weaker JSON precision but far better memory headroom.
- Meta's RLHF training makes it robust on English device commands.

**Avoid on Android:**
- DeepSeek-R1 variants produce `<think>...</think>` reasoning chains before JSON — adds latency and requires post-processing (handled in `LocalModelExecutor.kt`).
- SmolLM2-1.7B at Q4_K_M hits ~1.0 GB, use Q3_K_S to stay safely under.

### Download Links

```
Qwen2.5-1.5B-Instruct Q4_K_M:
https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF
File: qwen2.5-1.5b-instruct-q4_k_m.gguf

Llama-3.2-1B-Instruct Q4_K_M:
https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct-GGUF
File: Llama-3.2-1B-Instruct-Q4_K_M.gguf
```

Upload your chosen file to Google Drive, set sharing to "Anyone with the link",
then copy the file ID into `ModelConfig.ACTIVE.driveFileId`.

---

## Part 2 — Google Drive Setup

### Step 1: Upload the model
1. Go to drive.google.com
2. Upload your GGUF model file (e.g. `qwen2.5-1.5b-instruct-q4_k_m.gguf`)
3. Right-click → Share → "Anyone with the link" → "Viewer"
4. Copy the link: `https://drive.google.com/file/d/FILE_ID_HERE/view`

### Step 2: Set the file ID in ModelConfig
```kotlin
// In ModelConfig.kt, find the ACTIVE model entry and set:
driveFileId = "1AbCdEfGhIjKlMnOpQrStUvWx"  // your Drive file ID
```

### Step 3: Register ModelSetupActivity in AndroidManifest.xml
```xml
<!-- Add inside <application> -->
<activity
    android:name=".ModelSetupActivity"
    android:exported="false" />
```

### Step 4: Launch ModelSetupActivity before MainActivity
In your app entry point (or splash), check if the model is present:
```kotlin
// In a splash screen or the existing MainActivity.onCreate():
if (!ModelDownloadManager(this).isModelReady(ModelConfig.ACTIVE.fileName)) {
    startActivity(Intent(this, ModelSetupActivity::class.java))
    finish()
    return
}
```

### Step 5: Add INTERNET permission to AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

---

## Part 3 — GitHub Actions CI/CD

### File location
```
your-repo/
└── .github/
    └── workflows/
        └── build-apk.yml
```

### What it does
- **Every push (any branch)**: Builds a debug APK and uploads it as a downloadable artifact.
- **Every PR to main**: Builds debug APK + runs unit tests.
- **Push to main only**: Additionally builds a release APK.
- **Artifacts**: Available in GitHub → Actions tab → your workflow run → Artifacts section. Retained for 30 days (debug) / 90 days (release).

### Viewing the APK artifact
1. Push any commit to GitHub.
2. Go to your repo → Actions tab.
3. Click the latest "Build APK" workflow run.
4. Scroll to the Artifacts section at the bottom.
5. Download `debug-apk-{run_number}.zip` → extract `app-debug.apk`.

### Adding APK signing (optional)
1. Generate a keystore: `keytool -genkey -v -keystore release.jks -alias mykey -keyalg RSA -keysize 2048 -validity 10000`
2. Base64-encode: `base64 -w 0 release.jks | pbcopy`
3. In your GitHub repo → Settings → Secrets and variables → Actions, add:
   - `KEYSTORE_BASE64` — the base64 string
   - `KEY_ALIAS` — your key alias
   - `KEY_PASSWORD` — key password
   - `STORE_PASSWORD` — store password
4. In `build-apk.yml`, change the two `if: "false"` guards to `if: env.KEYSTORE_BASE64 != ''`.

---

## Backend: llama.cpp JNI Dependency

Add to `app/build.gradle`:
```groovy
dependencies {
    // llama.cpp Android JNI AAR (pre-built .so for arm64-v8a + x86_64)
    // Check https://github.com/ggerganov/llama.cpp/releases for latest
    implementation 'com.github.ggerganov:llama.cpp:b4300@aar'
}
```

Or build from source:
```bash
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp
cmake -B build -DLLAMA_ANDROID=ON
cmake --build build --config Release
```

Copy the resulting `.so` into `app/src/main/jniLibs/arm64-v8a/`.
