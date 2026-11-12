# ZhiCun · 智存

[![CI](https://github.com/senwang16/zhicun-client/actions/workflows/ci.yml/badge.svg)](https://github.com/senwang16/zhicun-client/actions/workflows/ci.yml)

**Capture anything. Find it with AI.**

ZhiCun is an Android-first personal knowledge app: save text, links, images, screenshots,
files and voice notes from any app, and get AI summaries, tags, OCR, transcripts and
semantic search on top of them. Everything is stored locally; AI processing is opt-in and
can use the official service or your own OpenAI-compatible providers.

> **Status: pre-1.0 (v0.1.0).** The app works and is used daily by the maintainer, but APIs,
> database schema and configuration formats may still change. **The app UI is currently
> Chinese-only**; an English UI is not available yet.

---

## Why another notes app

Most capture tools ask you to *file* things while you are still reading them. ZhiCun does the
opposite: capture first, organize later, and let retrieval do the work.

- **Capture is one gesture.** Share sheet, clipboard, floating bubble, widget or voice.
- **Local-first.** Records, images, audio and their derivatives live in the app's private
  storage. The app works without any account and without any cloud AI.
- **AI is replaceable.** Chat/summary, embeddings and speech-to-text are three independent
  providers. Use the official service or your own API keys.
- **Keywords still work.** Full-text search is on-device; semantic search is an addition,
  never a replacement.

## Features

| Area | What you get |
|---|---|
| Capture | System share sheet, floating bubble (clipboard / note / voice / screenshot), home-screen widget, manual note, in-app voice recorder |
| Content | Text, links, images, screenshots, audio/video files, PDFs and other files; multiple attachments per record |
| On-device | ML Kit Chinese/English OCR, HTML main-content extraction with saved snapshot, SQLite FTS4 keyword search |
| AI (opt-in) | Summary, short title, tags, speech transcription, embeddings for semantic search and related records |
| Organization | Inbox grouped by date, favorites, pin, archive, user + AI tags, collections (merge records into one summarized unit) |
| Search | Hybrid keyword + semantic ranking, search history, tag filtering, archived search |
| Privacy controls | Global cloud switch, on-device sensitive-content detection (API keys, phone/ID/bank card numbers), per-record confirmation before upload, independent confirmation before fetching a web page |
| Data ownership | ZIP export of records + attachments (+ optional AI derivatives), hard-delete and storage cleanup |

## How it works

```
┌────────────────────────── Android app ──────────────────────────┐
│ capture → Room (records / assets / derived_contents)            │
│            │                                                    │
│            ├─ OCR (ML Kit, on-device)                           │
│            ├─ web extract + snapshot (OkHttp + jsoup)            │
│            ├─ transcription  ─┐                                 │
│            ├─ summary/tags   ─┼─► AI Provider (official/custom)  │
│            └─ embeddings     ─┘                                 │
│                                                                 │
│ search = FTS4 keyword ∪ cosine over stored vectors              │
└─────────────────────────────────────────────────────────────────┘
```

Records and derived AI content are stored in separate tables: derived content is
rebuildable and can be regenerated at any time, original content is never overwritten by AI.
Vectors are stored with their model and provider identity, so switching embedding models
cannot silently mix incompatible vector spaces.

## Two ways to run the AI

1. **Official service** — point the app at the official gateway URL and paste an access token.
   The gateway holds the provider keys; the phone never sees them. The official service is
   operated by the maintainer and its server-side code is **not** part of this repository.
2. **Your own providers** — fill in API address, key and model for summary, embeddings and
   speech-to-text independently. Any OpenAI-compatible endpoint works (OpenAI, SiliconFlow,
   SenseNova, DeepSeek, Ollama, vLLM, ...). See [docs/ai-provider.md](docs/ai-provider.md).

Cloud processing is off until you turn it on. Without cloud AI the app still captures,
OCRs, extracts web pages, searches by keyword and exports. The client is fully usable with
your own providers; no server component is required.

## Build the Android app

Requirements: JDK 17+ (JDK 21 tested), Android SDK with platform 36 and build-tools 36.

```bash
cd android
# point Gradle at your SDK (or set ANDROID_HOME)
echo 'sdk.dir=/path/to/Android/Sdk' > local.properties

./gradlew :app:assembleDebug          # debug APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # JVM + Robolectric tests
```

On Windows use `gradlew.bat`. The debug APK is signed with the debug key and installs fine
for personal use (`adb install -r app/build/outputs/apk/debug/app-debug.apk`).

Optional: bake a default gateway into the build for your own testing —

```bash
./gradlew :app:assembleDebug -PaiGatewayUrl=https://your-gateway -PaiGatewayToken=...
```

or put the same two keys in `local.properties` (gitignored). **Never commit real tokens**;
release builds must not embed them.

## Documentation

- [docs/architecture.md](docs/architecture.md) — modules, data model, AI provider routing
- [docs/development.md](docs/development.md) — build, test, conventions, manual test checklist
- [docs/ai-provider.md](docs/ai-provider.md) — provider configuration and verified vendors
- [docs/faq.md](docs/faq.md) — common questions and failure modes
- [LICENSE](LICENSE) / [NOTICE](NOTICE) — license and third-party attributions

## Contributing

Issues and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) first. Security reports go through
[SECURITY.md](SECURITY.md), not public issues.

## Privacy

No account, no analytics SDK, no ads. Content stays on the device unless you enable cloud
AI; see [PRIVACY.md](PRIVACY.md) for the exact data flows.

## License

ZhiCun is licensed under the **Apache License 2.0** — see [LICENSE](LICENSE). It is a
permissive license with an express patent grant; you may use, modify and redistribute the
code, including commercially, provided you keep the license and attribution notices.

Third-party attributions and the one proprietary exception (the bundled Google ML Kit OCR
model, which is governed by Google's ML Kit terms and is **not** covered by Apache-2.0) are
listed in [NOTICE](NOTICE). The "ZhiCun" / "智存" name and the app icon are project marks.

Contributions are accepted under the same license; see [CONTRIBUTING.md](CONTRIBUTING.md).
No contributor license agreement is required.