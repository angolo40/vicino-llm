# NOTICE - VicinoLLM

VicinoLLM
Copyright 2026 [@angolo40](https://github.com/angolo40)

This product is licensed under the Apache License, Version 2.0 (the "License");
you may not use this product except in compliance with the License. You may
obtain a copy of the License at <http://www.apache.org/licenses/LICENSE-2.0>.

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.

---

## What VicinoLLM is (and what it is not)

VicinoLLM is an **inference runtime** for locally-hosted Large Language Models
on Android, built on top of Google's [LiteRT-LM SDK](https://ai.google.dev/edge/litert-lm).
It exposes an OpenAI-compatible HTTP API on the device's LAN interface so any
OpenAI-compatible client can talk to a model running on the phone.

VicinoLLM is **not**:

- A redistribution of Google's Gemma model weights.
- A "Gemma Derivative" as defined in the Gemma Terms of Use - we do not train,
  fine-tune, or modify any Gemma model.
- A hosted inference service. The server listens only on the user's own LAN.
  Model weights are downloaded directly by the end user from
  [litert-community on Hugging Face](https://huggingface.co/litert-community)
  under Google's Gemma Terms of Use, which apply separately between the end
  user and Google.

Model files (`*.litertlm`) are **never bundled** in the APK or published by
this project. The `ModelDownloader` component in VicinoLLM merely fetches a
file the user has chosen, directly from the user's Hugging Face account,
preserving the license acceptance flow that Hugging Face enforces.

---

## Third-party dependencies and their licenses

The following software is incorporated into, or required at runtime by,
VicinoLLM. Each dependency retains its original copyright and license; the
list below is provided for transparency and to satisfy the attribution
requirements of the respective licenses.

### Google - LiteRT-LM SDK

- Package: `com.google.ai.edge.litertlm:litertlm-android:0.13.1`
- License: Apache License 2.0
- Upstream: <https://github.com/google-ai-edge/LiteRT-LM>
- Used for: on-device model loading and inference (the sole reason this project
  exists as an Android-first app).

### JetBrains - Kotlin and Ktor

- Packages: `org.jetbrains.kotlin:*`, `io.ktor:ktor-server-core:3.0.3`,
  `io.ktor:ktor-server-cio:3.0.3`, `io.ktor:ktor-server-content-negotiation`,
  `io.ktor:ktor-serialization-kotlinx-json`, `io.ktor:ktor-server-status-pages`,
  `io.ktor:ktor-server-call-logging`
- License: Apache License 2.0
- Upstream: <https://kotlinlang.org/>, <https://ktor.io/>

### Google - Material Components for Android and AndroidX

- Packages: `com.google.android.material:material:1.12.0` and the AndroidX
  libraries listed in `gradle/libs.versions.toml`.
- License: Apache License 2.0
- Upstream: <https://github.com/material-components/material-components-android>,
  <https://developer.android.com/jetpack/androidx>

### Google - ZXing core

- Package: `com.google.zxing:core:3.5.3`
- License: Apache License 2.0
- Upstream: <https://github.com/zxing/zxing>
- Used for: on-device QR code generation in the About tab's crypto tip jar.

### Jonathan Hedley - Jsoup

- Package: `org.jsoup:jsoup:1.17.2`
- License: MIT License
- Upstream: <https://jsoup.org/>
- Used for: HTML parsing in the optional web-search augmentation (fetches and
  cleans the top N SearXNG results before injecting them into the prompt).

### Mozilla - pdf.js

- Version: 4.7.76
- License: Apache License 2.0
- Upstream: <https://mozilla.github.io/pdf.js/>
- Used for: rendering user-attached PDFs to images in the browser before upload.
  Distributed as two files under `app/src/main/assets/webui/assets/pdf.min.mjs`
  and `pdf.worker.min.mjs`.

### Kazuhiko Arase - qrcode-generator

- Version: 1.4.4
- License: MIT License
- Upstream: <https://github.com/kazuhikoarase/qrcode-generator>
- Used for: client-side QR generation in the bundled web UI.
- Distributed as `app/src/main/assets/webui/assets/qrcode.min.js`.

### highlight.js contributors

- License: BSD 3-Clause License
- Upstream: <https://highlightjs.org/>
- Used for: syntax highlighting of fenced code blocks in assistant replies.
- Distributed as `app/src/main/assets/webui/assets/hljs.min.js`.

### Kotlinx Coroutines and Kotlinx Serialization

- Packages: `org.jetbrains.kotlinx:kotlinx-coroutines-android`,
  `org.jetbrains.kotlinx:kotlinx-serialization-json`
- License: Apache License 2.0
- Upstream: <https://github.com/Kotlin/kotlinx.coroutines>,
  <https://github.com/Kotlin/kotlinx.serialization>

---

## Model families supported by VicinoLLM

VicinoLLM supports the models Google has published as `.litertlm` bundles via
LiteRT-LM's native `EngineConfig`. As of April 2026, that family is:

- **Gemma 4 E2B** (`gemma-4-E2B-it.litertlm`)
- **Gemma 4 E4B** (`gemma-4-E4B-it.litertlm`)

These files are hosted by Google's `litert-community` organisation on
Hugging Face. Downloading them requires the user to accept Google's Gemma
Terms of Use directly with Google / Hugging Face - VicinoLLM does not
relicense, mirror, or modify the model files.

The Gemma Terms of Use are available at
<https://ai.google.dev/gemma/terms>. A copy of the Prohibited Use Policy is
at <https://ai.google.dev/gemma/prohibited_use_policy>. By downloading and
running a Gemma model, the end user agrees to those terms **directly with
Google**; no rights or obligations flow through VicinoLLM or its author.

---

## Trademarks

"Android", "Gemma", "LiteRT-LM", "Material", and "Google" are trademarks of
Google LLC. "Kotlin" and "Ktor" are trademarks of JetBrains s.r.o.
"Samsung", "Galaxy" and "OneUI" are trademarks of Samsung Electronics Co., Ltd.
All other product names, logos, and brands are property of their respective
owners. Use of these names, logos, and brands in this project's documentation
is for identification purposes only and does not imply endorsement.

---

## Questions about licensing

If you are considering redistributing VicinoLLM, forking it for commercial
use, or integrating it with another product, the quick answer is: the Apache
2.0 licence permits all of that provided you keep the copyright notice and
this NOTICE file intact. For Gemma model weights, your relationship is with
Google, not with this project.

If anything here is unclear or you spot an error, open an issue at
<https://github.com/angolo40/vicino-llm/issues>.
