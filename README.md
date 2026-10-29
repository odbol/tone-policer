# Tone Policer

An Android accessibility service that watches what you type in **any app** and asks Gemini whether it
might come across as inappropriate, inflammatory, unprofessional, insulting, standoffish,
passive-aggressive or otherwise socially inept.

- The focused text field gets a **glowing, pulsing border**. Its color runs from **green → yellow → red**
  as the severity score (0–100) goes up, and worse text pulses faster.
- Once the score passes your threshold, a small **"✎ Fix tone"** button shows up above the field. Tap it
  to get an explanation and three rewrites. Tap a rewrite to replace the field's text, or long-press it
  to copy.
- Password fields, the keyboard's own fields, and this app's settings fields are never read.

## How it works

```
 typing ─► AccessibilityEvent (TEXT_CHANGED / FOCUSED)
            │  debounce 900 ms, skip passwords, LRU cache
            ▼
     InferenceEngine.analyze() ──► {"severity": 0-100, "categories": [...], "reason": "..."}
            │
            ▼
     OverlayController (TYPE_ACCESSIBILITY_OVERLAY windows, no SYSTEM_ALERT_WINDOW needed)
       ├─ GlowBorderView: blurred, pulsing round-rect around the field's screen bounds
       ├─ "Fix tone" pill above the field ──tap──► InferenceEngine.suggest()
       └─ SuggestionPanel ──tap──► ACTION_SET_TEXT on the field (falls back to the clipboard)
```

Scroll, layout and window events re-anchor the overlay to the field, and it hides when the field
loses focus.

| File | Role |
| --- | --- |
| `service/ToneAccessibilityService.kt` | Tracks the focused field, debounces, calls the engine, applies rewrites |
| `overlay/OverlayController.kt` | Adds and positions the three overlay windows |
| `overlay/GlowBorderView.kt` | The glowing border (software layer + `BlurMaskFilter`) |
| `overlay/SuggestionPanel.kt` | The suggestions card |
| `inference/Prompts.kt` | Prompt templates plus lenient JSON parsing shared by all Gemini backends |
| `inference/*Engine.kt` | The inference backends (below) |
| `ui/MainActivity.kt` | Settings, model download, in-app tester |

## Inference options

Pick one in the app under **2. Inference engine**. Every backend implements the same
`InferenceEngine` interface (`analyze` + `suggest`), so adding another one only touches
`EngineFactory`.

| Option | Status | Privacy | Devices | Cost / setup | Quality and latency |
| --- | --- | --- | --- | --- | --- |
| **A. On-device Gemini Nano via ML Kit GenAI Prompt API** (`OnDeviceGeminiEngine`) | ✅ Implemented | Text stays on the phone | AICore devices only (Pixel 9+, Galaxy S25+, some others) | Free; one-time model download (button in the app) | Good at scoring, decent at rewrites; roughly 0.3–1 s |
| **B. Cloud Gemini via the Gemini Developer API (REST + API key)** (`CloudGeminiEngine`) | ✅ Implemented | Text goes to Google | Any device with internet | Free tier available; get a key at [aistudio.google.com/apikey](https://aistudio.google.com/apikey) | Best quality; roughly 0.5–1.5 s with `gemini-flash-lite-latest` (any model name works) |
| **C. Auto** (`AutoEngine`) | ✅ Implemented, default | On-device when possible | Any | Uses whatever is set up | Tries A, then B (if a key is set), then D. Falls through on errors |
| **D. Offline keyword heuristic** (`HeuristicEngine`) | ✅ Implemented | On the phone | Any, including emulators | None | Crude. For demos and for testing the overlay |
| E. ML Kit GenAI **Rewriting** API (`com.google.mlkit:genai-rewriting`) | Not implemented | On the phone | AICore devices | Free | Purpose-built rewrites (`PROFESSIONAL`, `FRIENDLY`, `REPHRASE` …). It can't score severity, so it would pair with A for `suggest()` |
| F. **Firebase AI Logic** (`com.google.firebase:firebase-ai`) | Not implemented | Text goes to Google | Any | Needs a Firebase project and `google-services.json` | Same models as B, but no API key ships in the APK, and you get App Check and quotas. The right choice for a Play Store release |
| G. Gemma via MediaPipe / LiteRT-LM | Not implemented | On the phone | Most recent phones | Free; you bundle or download a model of about 0.5–2 GB | Works on non-AICore phones; slower, and the app gets bigger |

**Recommendation:** use **Auto** for personal use (private and free when Nano is available, with the
cloud only as backup). For a public release, swap B for **F (Firebase AI Logic)** so no API key ships
in the app.

### Caveats worth knowing

- **AICore and the foreground:** ML Kit GenAI may reject inference from an app that isn't in the
  foreground (`BACKGROUND_USE_BLOCKED`). An accessibility service usually runs while another app is on
  top, so on-device scoring may fail in some apps or on some builds. Auto mode catches this and moves
  on to the cloud or heuristic backend. The in-app "Try it" box always works, because the app is in the
  foreground there.
- **Gemini Nano context** is small, so texts are cut to the last 1,500 characters.
- **Safety filters:** cloud Gemini uses `BLOCK_ONLY_HIGH`. If Gemini refuses to read a text at all,
  it's scored 100.
- **Play Store policy:** Google restricts the Accessibility API to apps that help people with
  disabilities, unless the use is declared and approved. Expect to sideload, or to go through the
  declaration process.
- The API key is stored in app-private SharedPreferences, which is fine for personal use (see F).

## Building

Requirements: Android Studio (or the command-line SDK) with SDK 36, and JDK 17+.

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then open **Tone Policer**:

1. Tap **Open accessibility settings** and turn on *Tone Policer*.
2. Pick an inference engine. For on-device, tap **Download Gemini Nano**. For cloud, paste an API key.
3. Type in the **Try it** box, or in any other app.

Dependency versions to check when you first sync: AGP `8.13.0`, Kotlin `2.2.20`,
`com.google.mlkit:genai-prompt:1.0.0-alpha1` (newer versions may exist; the API used here is
`Generation.getClient()`, `checkStatus()`, `download()` and `generateContent(generateContentRequest(TextPart(..)) { … })`).
