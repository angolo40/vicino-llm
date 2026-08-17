# VicinoLLM — your LLM, close to you

**v1.0.1** · Kotlin · Ktor · LiteRT-LM · Gemma 4

> *Vicino* is Italian for "near". The point is simple: your language
> model runs on the phone in your pocket, the traffic stays on your LAN,
> and nothing ever touches a cloud.

VicinoLLM turns your Android phone into a personal OpenAI-compatible LLM
server. Load a Gemma 4 model once, then chat from any client that speaks
the OpenAI API — the official Python/JS SDKs, LangChain, llama.cpp
clients, OpenWebUI, whatever. The phone runs the inference, your LAN
carries the traffic, nothing touches a cloud.

A bundled web UI (ChatGPT-style, dark, multi-chat, markdown, themes,
push-to-talk, drag-and-drop images/PDF/audio) is served from the same
`:8080` endpoint — open `http://<phone-ip>:8080/` in any browser on the
same Wi-Fi.

> ⚠️ **LAN-only.** Port 8080 exposes full inference to anyone who can
> reach it. The server supports an optional API key (Settings → Server
> API key) and a web-UI bypass header so the local UI keeps working. For
> remote access use Tailscale / WireGuard / NetBird. Never expose
> directly to the internet.

---

## Features

### Server

- **OpenAI-compatible API** (`/v1/chat/completions`, `/v1/completions`,
  `/v1/models`, `/v1/models/load`, `DELETE /v1/models/{id}`,
  `/v1/embeddings` → 501). Polymorphic `content` field: plain string or
  multimodal parts array.
- **Server-Sent Events streaming** — both text and multimodal requests
  stream token-by-token via `Flow<Message>` from the LiteRT-LM SDK.
- **Multi-model routing** — several engines can be loaded at once,
  request's `model` field picks which one.
- **Multimodal in** — text + image(s) + audio. Images resized to 768 px
  client-side before upload. PDFs rendered to page images via pdf.js
  (first 4 pages, model cap). Audio accepts WAV/MP3/OGG/M4A.
- **Auto-restore** — after the Samsung low-memory killer terminates the
  service, `START_STICKY` brings it back and the last loaded model is
  reloaded automatically.
- **503 on no model** — clients get a structured error, the web UI shows
  a "reload the model" hint.

### Bundled web UI (`http://<phone-ip>:8080/`)

- Multi-chat sidebar, persisted in `localStorage`
- Markdown rendering with highlight.js fenced code
- Streaming tokens as they arrive
- Drag-and-drop or pick: images (`image/*`), PDFs (rendered via
  pdf.js), audio (`audio/*`) — or **push-to-talk** via `MediaRecorder`
  (requires HTTPS / localhost, see caveat below)
- Themes: dark / light / auto
- Sampling modal: temperature, top-p, top-k, max tokens, context
  window, stream toggle, seed, with plain-language hints
- Crypto tip jar in About with tabbed QR codes generated
  client-side — no Ko-fi / BMC / Stripe middleware
- HTTP-safe clipboard fallback for `copy` (plain-HTTP LAN context)

### Android app (Material 3)

- **Floating bottom navigation** (Instagram / Cake Wallet style) —
  Server · Models · Settings · Log · About
- Live request log (last 50 requests, in-memory)
- Model downloader with HF token support (curated list: Gemma 4 E2B /
  E4B), resumable via HTTP Range
- Context-window slider (1 k – 32 k tokens, applied at load)
- Sampling defaults that fill in missing request fields
- Foreground service with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` + a
  partial wake lock; persistent notification with IP, port, request
  counter, Stop button
- About tab with crypto QR tips (ZXing-rendered) and licenses

---

## Supported models

LiteRT-LM 0.13.1 loads `.litertlm` bundles that pack a TFLite graph +
vision/audio encoders + tokenizer. Officially supported today:

| Model | Size | Vision | Audio | Context | Notes |
|---|---|---|---|---|---|
| **Gemma 4 E2B** | 1.5 GB | ✅ | ✅ | 32 k | Fast. First choice for S10-class devices |
| **Gemma 4 E4B** | 3.65 GB | ✅ | ✅ | 32 k | Sharper, but OOM-prone on <12 GB RAM devices |

Non-Gemma models (GLM-OCR, Qwen-VL, Phi-3.5-vision, …) are **not
supported** — LiteRT-LM's pipeline is hardcoded for the Gemma
architecture. Use a different runtime (llama.cpp JNI, MLC-LLM) if you
need other families.

---

## Tech stack

- Kotlin **2.2.21**, AGP **8.9.1**, Gradle **8.11.1**, JDK 17
- `com.google.ai.edge.litertlm:litertlm-android:0.13.1`
- Ktor **3.0.3** CIO engine (Netty NIO is unusable on Android)
- ZXing core 3.5.3 (QR rendering for the About tab)
- Web UI: vanilla HTML/CSS/JS, pdf.js 4.7, qrcode-generator 1.4,
  highlight.js — all bundled, no CDN at runtime
- minSdk 31 (Android 12), targetSdk 34, `arm64-v8a` only
- Zero Firebase / analytics / Play Services dependencies

---

## Build

Windows / macOS / Linux, JDK 17, Android SDK with `platforms/android-34`
+ `build-tools/34.0.0`, `ANDROID_HOME` set:

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk  (~42 MB)
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launcher label: **VicinoLLM**.

Grant notifications (required for the foreground service):

```bash
adb shell pm grant com.sectl.litertlm.server android.permission.POST_NOTIFICATIONS
```

---

## Loading a model

Two ways.

### From the app (recommended)

Open **VicinoLLM** → **Models** → browse the curated list. Tap
**Download** (you'll be prompted for a Hugging Face token if the model
is gated — paste it in Settings first). When the download finishes the
button switches to **Load**. Tap it again to load the model into the
engine.

Paths:
- Default: `/sdcard/Models/`
- Per-app fallback if scoped storage blocks the above:
  `/sdcard/Android/data/com.sectl.litertlm.server/files/`

### Manual push

```bash
adb push "gemma-4-E2B-it.litertlm" /sdcard/Models/
```

Then tap **Refresh** in Models and **Load** the file.

---

## API reference

| Endpoint | Body | Notes |
|---|---|---|
| `GET /health` | — | `{"status":"ok","model":"…","backend":"gpu\|cpu\|none","loaded_models":[…]}` |
| `GET /v1/models` | — | OpenAI-shape list of currently loaded engines |
| `GET /v1/models/available` | — | (non-OpenAI) lists .litertlm files on device |
| `POST /v1/models/load` | `{path, backend}` | Load a local file, returns when loading started |
| `DELETE /v1/models/{id}` | — | Unload one engine by filename |
| `POST /v1/chat/completions` | OpenAI | Streaming via `stream:true`. Multimodal via content parts |
| `POST /v1/completions` | OpenAI legacy | Raw `prompt` string, no chat template |
| `POST /v1/embeddings` | OpenAI | Returns 501 — not implemented |

Multimodal content shape (matches OpenAI):

```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "Describe this image"},
    {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,…"}},
    {"type": "input_audio", "input_audio": {"data": "…base64…", "format": "wav"}}
  ]
}
```

### Sampling knobs

| Field | Default | Range |
|---|---|---|
| `temperature` | 0.8 | 0 – 2 |
| `top_p` | 0.95 | 0 – 1 |
| `top_k` | 40 | 1 – 200 |
| `max_tokens` | 512 | 16 – 4096 |
| `seed` | 0 (random) | any int |

Ignored silently: `presence_penalty`, `frequency_penalty`, `logit_bias`,
`n`, `stop`, `user`, `tools` (no tool-calling today).

---

## Example clients

```bash
PHONE=http://192.168.1.50:8080

curl $PHONE/health

curl $PHONE/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <key-if-you-set-one>' \
  -d '{
    "model": "gemma-4-E2B-it.litertlm",
    "messages": [{"role":"user","content":"What is 2+2?"}],
    "max_tokens": 60,
    "stream": true
  }'
```

### OpenAI Python SDK

```python
from openai import OpenAI
client = OpenAI(base_url="http://192.168.1.50:8080/v1", api_key="unused")
stream = client.chat.completions.create(
    model="gemma-4-E2B-it.litertlm",
    messages=[{"role":"user","content":"hi"}],
    stream=True,
)
for chunk in stream:
    print(chunk.choices[0].delta.content or "", end="", flush=True)
```

### OpenWebUI

Add a custom OpenAI endpoint: `http://<phone-ip>:8080/v1`, any API key.
Pick the model from the dropdown.

---

## Authentication

- **No key set** (default): open LAN server
- **Key set** (Settings → Server API key): clients must send
  `Authorization: Bearer <key>`
- **Web UI bypass**: the bundled web UI sends `X-Vicino-UI: 1` + a
  same-origin Referer, which skips the key check. External clients
  obviously don't have this, so the key still protects against them.

---

## Performance (measured)

| Device | Model | Backend | Warm decode tok/s |
|---|---|---|---|
| Samsung S10 (Mali-G76) | Gemma 4 E2B | GPU | 13.76 |
| Samsung S24 Ultra (Adreno 750) | Gemma 4 E2B | GPU | 32.78 |
| Samsung S10 | Gemma 4 E2B | CPU | 2.7 |

Cold load is 30–60 s (first time the XNNPACK weight cache is built).
Warm load is ~4 s.

---

## Known caveats

- **Microphone in web UI** requires HTTPS or localhost. On plain-HTTP
  LAN (`http://10.x.y.z:8080`) `getUserMedia` is blocked by the browser.
  Workaround: Chrome's `chrome://flags/#unsafely-treat-insecure-origin-as-secure`
  → allowlist the phone IP, or access via `adb reverse tcp:8080 tcp:8080`
  → `http://localhost:8080`. The Attach button (microphone → voice memo
  app → attach recording) works in all browsers.
- **Samsung mem-pressure killer** can terminate the service during heavy
  multimodal work with E4B. The service restarts automatically
  (START_STICKY + auto-restore) but requests in flight fail. Drop to E2B
  if it happens repeatedly.
- **First multimodal turn** on a freshly loaded model takes longer than
  subsequent ones because the vision encoder lazy-allocates its slabs.
- **Clipboard** over plain HTTP uses a hidden-textarea fallback; works
  everywhere but shows a brief focus flash.

---

## Project layout

```
app/
├── src/main/
│   ├── java/com/sectl/litertlm/server/
│   │   ├── MainActivity.kt            # Bottom nav + ViewPager
│   │   ├── ServerService.kt           # Foreground service, auto-restore
│   │   ├── LiteRtLmEngine.kt          # Real engine (calls LiteRT-LM SDK)
│   │   ├── StubInferenceEngine.kt     # Fallback if SDK ctor fails
│   │   ├── HttpServer.kt              # Ktor wiring, route handlers
│   │   ├── EngineHolder.kt            # Multi-engine registry
│   │   ├── ModelRepository.kt         # Filesystem scan for .litertlm
│   │   ├── ModelDownloader.kt         # HF download + SHA check + resume
│   │   ├── Prefs.kt                   # SharedPreferences wrapper
│   │   └── ui/                        # Fragments for each tab
│   ├── res/                           # Layouts, colors, themes, drawables
│   └── assets/webui/                  # Bundled web UI (HTML/CSS/JS)
├── build.gradle.kts
└── …
```

---

## Distribution

VicinoLLM is distributed through **GitHub Releases** as a signed APK and,
once listed, through community repositories such as **F-Droid** and
**IzzyOnDroid**. It is not intended for the Google Play Store in its
current configuration: the crypto tip jar in the About tab is fine for
sideload and community repos but overlaps with Play's payments policy.
A future `play` build variant without the crypto block may be added if
there is demand.

---

## Support

VicinoLLM is free. Zero cloud, zero ads, zero telemetry. If it's useful
to you, a crypto tip keeps development going — addresses with QR codes
are in the **About** tab. No Ko-fi, no Stripe, no KYC.

- **Source**: <https://github.com/angolo40/vicino-llm>

---

## License

**VicinoLLM code**: Apache License 2.0 (see [LICENSE](./LICENSE) and
[NOTICE.md](./NOTICE.md) for full attribution).

**Important — VicinoLLM is not a "Gemma Derivative".** VicinoLLM is an
*inference runtime* built on Google's [LiteRT-LM SDK](https://ai.google.dev/edge/litert-lm).
It does not train, fine-tune, or modify any Gemma model. Gemma model
files (`*.litertlm`) are **never bundled** in this APK and are never
redistributed by this project. Users download them directly from
[litert-community on Hugging Face](https://huggingface.co/litert-community)
under Google's [Gemma Terms of Use](https://ai.google.dev/gemma/terms),
which apply **directly between the user and Google**. VicinoLLM is not a
hosted inference service: the HTTP server listens only on the user's own
LAN interface by default.

**Third-party dependencies**: LiteRT-LM (Apache 2.0), Ktor (Apache 2.0),
Material Components (Apache 2.0), AndroidX (Apache 2.0), ZXing (Apache 2.0),
Jsoup (MIT), highlight.js (BSD-3), pdf.js (Apache 2.0),
qrcode-generator (MIT). See [NOTICE.md](./NOTICE.md) for the complete list,
versions, upstream links, and their use in the project.
