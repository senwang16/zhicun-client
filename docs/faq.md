# FAQ

## General

**Do I need an account?**
No. There is no sign-in, no sync service, and no analytics. All records live on the device.

**Is there a cloud backup or multi-device sync?**
No. Use Settings → 数据 → 导出全部记录 to move data between devices; restoring an export is
not automated yet.

**Why is the UI Chinese?**
The product was built for a Chinese-speaking user base. Internationalization is on the
backlog; the docs are bilingual, the UI is not yet.

**Which model does it use?**
Whatever you configure. There is no built-in model. You can point summary, embeddings and
transcription at three different OpenAI-compatible providers, or at a gateway.

**Can it work completely offline?**
Yes for capture, OCR, web extraction, keyword search and export. Summary/tags/transcription and
semantic search need a configured AI provider.

## AI configuration

**Where do I paste my API key?**
Settings → AI 服务配置 → switch off "使用官方服务", then fill the three cards. Each card has
its own Save button. The key is stored encrypted and is never shown again; leave the field
empty when you only want to change the model name.

**Can I mix providers?**
Yes. For example: OpenAI for summaries, SiliconFlow `bge-m3` for embeddings, their
`SenseVoiceSmall` for transcription. Ollama works for summaries and embeddings; there is no
standard local ASR endpoint.

**Why does saving a new key not show it?**
The app never reads keys back into the UI. Re-enter the key to change it; leaving the field
empty keeps the stored one.

**I changed the embedding model and semantic search found nothing.**
Old vectors are excluded because they belong to a different vector space. Open
Settings → AI 服务配置 → 向量状态, press 重新向量化, and wait for the queued `EMBED` jobs.
Records held back by the cloud switch or by sensitive-content confirmation stay pending.

**Which errors are retried automatically?**
Network failures and 5xx are retried with backoff; 429 rate limits back off without consuming
the retry budget; quota exhaustion, auth failures and bad model names are terminal so the app
does not waste your quota.

## Capture and processing

**A record is stuck on "处理中".**
Open the record and use 重新处理. If cloud AI is off or the provider is not configured, the
task is intentionally not queued — the record shows 仅本地 instead. For text, check that the
provider is reachable (Android cannot reach `workers.dev`; use a custom domain or your own
provider).

**The summary says "没有可摘要的文字内容".**
The record has no usable text: a PDF without a text layer, a photo without text, a silent
recording, or audio longer than the 5-minute transcription limit. That is a success with an
empty result, not a failure.

**OCR found nothing.**
ML Kit's Chinese model handles printed Chinese/Latin text; handwriting and very small text are
unreliable. The original image is always kept.

**Web page extraction failed.**
Some sites block non-browser clients, require login, or serve the article only via JavaScript.
The URL and the record are kept; you can retry from the detail page. Suspected-sensitive
records require an explicit confirmation before the request.

**Can I record longer than 5 minutes?**
You can record and play any length; transcription is skipped above 5 minutes or 20 MB by
policy, and the app tells you so instead of failing silently.

**Why did my duplicate capture not appear?**
Identical text from the same source is deduplicated. Edit the existing record instead.

## Privacy and storage

**What is sent to the AI provider?**
For summaries/embeddings: record text, truncated to 4000 characters. For transcription: the
audio file. For search: the query string to compute its vector. Nothing else. See
`PRIVACY.md`.

**Does the official gateway see my content?**
It forwards it to the provider; it does not store it. It stores anonymous metrics (route,
duration, error code, provider attempts, salted install-id hash). If you want to avoid even
that, configure your own providers directly and do not use the gateway.

**What happens when I uninstall?**
The app's private directory is deleted, including attachments. Export first.

## Development

**The Android build cannot find the SDK.**
Create `android/local.properties` with `sdk.dir=...` (or set `ANDROID_HOME`). See
`docs/development.md`.

**Tests fail with a Robolectric download error.**
The first run downloads `android-all` jars. The build points at an Aliyun mirror; from outside
China you may need to change `robolectric.dependency.repo.url` locally.

**Is the server/gateway code included in this repository?**
No. The public repository contains the Android client only. The official gateway is operated
privately by the maintainer; end users do not need it because the client can call any
OpenAI-compatible provider directly. There is no self-hosting guide in this repository.

**Is the official service free?**
It is a **free trial**: the gateway applies per-install, per-network and global daily limits
(the app enforces no local record quota). The app turns those responses into a terminal
"trial used up / daily quota reached" message that points at configuring your own provider.
If a paid tier is added later, it will be documented in the app and in `CHANGELOG.md` before it
takes effect. Configuring your own OpenAI-compatible or Anthropic provider is never limited by
the official quota.

**Can I use the code commercially?**
Yes. ZhiCun is licensed under the Apache License 2.0, which permits commercial use,
modification and redistribution with an express patent grant. Keep `LICENSE` and `NOTICE`
with any distributed build. Note that the bundled Google ML Kit OCR model is **not** covered
by Apache-2.0 — see `NOTICE`.