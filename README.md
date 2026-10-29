# Tone Policer

An Android accessibility service that watches what you type in **any app** and asks Gemini whether it
might come across as inappropriate, inflammatory, unprofessional, insulting, standoffish,
passive-aggressive or otherwise socially inept.

- The focused text field gets a **glowing, pulsing border**. Its color runs from **green → yellow → red**
  as the severity score (0–100) goes up, and worse text pulses faster.
- It reads the **rest of the screen** (the chat history or email above the field) so it can tell
  whether your reply fits the conversation, not just whether the words are rude.
- Once the score passes your threshold, a small **"✎ Fix tone"** button shows up above the field. Tap it
  for an explanation, a "Consider:" checklist of social cues you missed, and **three distinct rewrites**:
  *Light touch*, *Warm* and *Professional*. Tap a rewrite to replace the field's text, or long-press it
  to copy.
- Password fields, the keyboard's own fields, and this app's settings fields are never read.

## Social expectations it checks

Besides rude wording, the prompt asks Gemini to check the reply against the conversation on screen,
and to flag only the expectations that apply:

- **Acknowledge** the other person's point, question or effort before replying or disagreeing
- **Empathize** with feelings, stress or bad news ("That sounds really frustrating")
- **Celebrate** good news ("Congratulations!")
- **Thank** them for help, time, information or compliments
- **Apologize** when at fault, late or confused ("Sorry, I didn't quite understand that")
- **Politeness:** please, and softened requests ("Could you…" rather than "Send it")
- **Answer** the question they actually asked
- **Return the courtesy:** if they asked how you are, ask back
- **Match their effort** and formality; a one-word reply to a heartfelt message feels cold
- **Give a reason or alternative** when saying no
- **Disagree gently** with "I" statements instead of blame or absolutes
- **Don't make it about yourself** when they share something personal
- **Avoid sarcasm** and curtness that read as hostile in text
- **Greetings and sign-offs** where the medium expects them
- **Confirm next steps** when they're relying on you

Missed cues come back as short to-dos (e.g. "Acknowledge that their flight got cancelled") and raise
the severity score: 16–40 for a missing nicety, 41–70 for ignoring someone's feelings. The list lives
in `Prompts.SOCIAL_EXPECTATIONS`.

### Screen context

`ScreenContextExtractor` walks the field's window for visible, non-editable, non-password text above
the field. It keeps the roughly 2,500 characters closest to the field, which is where the newest
messages are. Chat bubbles get a `[left]` tag (usually the other person) or `[right]` (usually you),
based on where they sit horizontally. You can turn this off in settings. In cloud mode this text is
sent to Google along with your message.

## How it works

```
 typing ─► AccessibilityEvent (TEXT_CHANGED / FOCUSED)
            │  debounce 900 ms, skip passwords, LRU cache
            ▼
     ScreenContextExtractor ──► visible conversation above the field
            ▼
     ToneAnalyzer.analyze() ──► {"severity", "categories", "reason", "missing": [...]}
            │
            ▼
     OverlayController (TYPE_ACCESSIBILITY_OVERLAY windows, no SYSTEM_ALERT_WINDOW needed)
       ├─ GlowBorderView: blurred, pulsing round-rect around the field's screen bounds
       ├─ "Fix tone" pill above the field ──tap──► RewriteSuggester.suggest()
       └─ SuggestionPanel ──tap──► ACTION_SET_TEXT on the field (falls back to the clipboard)
```

Scroll, layout and window events re-anchor the overlay to the field, and it hides when the field
loses focus.

| File | Role |
| --- | --- |
| `service/ToneAccessibilityService.kt` | Tracks the focused field, debounces, calls the analyzer and suggester, applies rewrites |
| `overlay/OverlayController.kt` | Adds and positions the three overlay windows |
| `overlay/GlowBorderView.kt` | The glowing border (software layer + `BlurMaskFilter`) |
| `overlay/SuggestionPanel.kt` | The suggestions card |
| `service/ScreenContextExtractor.kt` | Collects the on-screen conversation around the field |
| `inference/Prompts.kt` | Prompt templates, the social-expectations list, lenient JSON parsing, and the top-up that guarantees 3 rewrites |
| `inference/ToneModels.kt` | The `ToneAnalyzer` and `RewriteSuggester` interfaces and their data types |
| `inference/*Engine.kt` | The inference backends (below) |
| `inference/Fallback.kt` | The "Auto" chains for each interface |
| `ui/MainActivity.kt` | Settings, model download, in-app tester |

## Inference options

Analysis and suggestions are separate jobs with separate interfaces, and you pick a backend for each
under **2. Inference engine**:

- `ToneAnalyzer.analyze()` scores the tone. It runs on every pause in typing, so it needs to be fast and
  cheap.
- `RewriteSuggester.suggest()` writes the three rewrites. It runs only when you tap "Fix tone", so it
  can be slower and needs a generative model.

A backend can implement either interface or both. Adding one only touches `EngineFactory`.

| Option | Status | Privacy | Devices | Cost / setup | Quality and latency |
| --- | --- | --- | --- | --- | --- |
| **A. On-device Gemini Nano via ML Kit GenAI Prompt API** (`OnDeviceGeminiEngine`) | ✅ Implemented | Text stays on the phone | AICore devices only (Pixel 9+, Galaxy S25+, some others) | Free; one-time model download (button in the app) | Good at scoring, decent at rewrites; roughly 0.3–1 s |
| **B. Cloud Gemini via the Gemini Developer API (REST + API key)** (`CloudGeminiEngine`) | ✅ Implemented | Text goes to Google | Any device with internet | Free tier available; get a key at [aistudio.google.com/apikey](https://aistudio.google.com/apikey) | Best quality; roughly 0.5–1.5 s with `gemini-flash-lite-latest` (any model name works) |
| **C. Auto** (`FallbackAnalyzer` / `FallbackSuggester`) | ✅ Implemented, default | On-device when possible | Any | Uses whatever is set up | Tries A, then B (if a key is set), then D. Falls through on errors |
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
- **Gemini Nano context** is small, so the message is cut to its last 1,200 characters and the screen
  context to its last 1,800.
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
