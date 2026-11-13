# AI Provider configuration

ZhiCun never assumes that one vendor provides all three AI capabilities. Summary, embeddings
and speech-to-text are configured **independently**; they may point at three different
vendors, at the official gateway, or at a mixture.

This document describes what the app sends, how to fill the settings screen, and what the
error messages mean. It is written for users who bring their own keys, and for maintainers
who add support for another vendor.

> Status: the direct-provider protocol layer is covered by unit tests against a local mock
> HTTP server. **Real-vendor connectivity from the phone is still pending manual acceptance**
> (see `docs/development.md`). Do not treat the "verified" column below as a guarantee for
> the current app version.

## The three providers

| Capability | Settings card | Endpoint used | Request | Response fields used |
|---|---|---|---|---|
| Summary + tags | 摘要与标签 (Chat) | OpenAI: `POST {base}/chat/completions` · Anthropic: `POST {base}/messages` | OpenAI: `model`, `messages`, `temperature`, `max_tokens`, optional `response_format` · Anthropic: `model`, `system`, `messages`, `max_tokens` | OpenAI: `choices[0].message.content` · Anthropic: concatenated `content[].text` (text containing JSON) |
| Embeddings | 向量（语义搜索） | `POST {base}/embeddings` | `model`, `input` | `data[0].embedding`, `model` |
| Speech-to-text | 语音转写 (ASR) | `POST {base}/audio/transcriptions` | multipart: `file` (audio), `model` | `text` |

All three use `Authorization: Bearer <API Key>` when a key is set. An empty key means "send
no Authorization header" — that is how local Ollama / vLLM / LM Studio deployments usually
work.

### Base URL rules

Enter the URL up to the API version, for example:

- `https://api.openai.com/v1`
- `https://api.siliconflow.cn/v1`
- `http://127.0.0.1:11434/v1` (Ollama on the same device)

The app appends the path itself. If you enter only a host (`https://api.siliconflow.cn`),
`/v1` is added automatically. Trailing slashes are normalized.

**Cleartext `http://` is allowed for loopback addresses only** — `localhost`, `127.0.0.1`,
`::1`, and `10.0.2.2` (the emulator's alias for the host machine). That is enough for a
model server running on the same device or in the emulator's host. Any other host must use
`https://`: the platform's network security policy rejects cleartext to a LAN or public
address before the request is sent, so your API key is never transmitted unencrypted over a
network. The allow-list lives in `android/app/src/main/res/xml/network_security_config.xml`;
the app reports such a rejection as `INSECURE_URL` with an explanation rather than as a
generic network failure.

A model server on another machine in your LAN therefore needs TLS (a self-signed
certificate plus a trust anchor), or a local reverse proxy on the phone itself.

Do not append `/chat/completions` or `/embeddings` yourself — they are added by capability.

### Which protocol is spoken

Chat supports the two widely used "generic" formats, selected with the **接口协议** chips on the
chat card:

| | OpenAI-compatible | Anthropic Messages |
|---|---|---|
| Endpoint | `{base}/chat/completions` | `{base}/messages` |
| Auth | `Authorization: Bearer <key>` | `x-api-key: <key>` + `anthropic-version: 2023-06-01` |
| System prompt | a `system` message | top-level `system` field |
| JSON mode | optional `response_format` (auto-falls back when rejected) | not available; the prompt alone asks for JSON |
| Response text | `choices[0].message.content` | `content[]` blocks of `type: "text"` |

Embeddings and speech-to-text have **only** the OpenAI-compatible form: Anthropic does not
offer those APIs, and the other vendors in the table below expose OpenAI-compatible endpoints
for them.

Native protocols of other chat vendors are still not supported — for example Google Gemini's
native API or AWS Bedrock's SigV4-signed API. If a vendor offers an OpenAI-compatible endpoint,
use that URL; otherwise use a translation proxy.

## What gets sent

- **Summary**: the record's text (body, OCR text, transcript, web article, collection
  description + members), truncated to 4000 characters. The prompt is built on the device
  and asks for `{"title","summary","tags"}`. The request also carries the output language
  (`language: "zh" | "en"` for the official gateway; a fixed instruction appended to the
  system prompt for direct providers), so titles/summaries/tags follow the app language while
  the original text is never translated.
- **Embeddings**: the same record text truncated to 4000 characters, and on search, the
  query string itself.
- **Speech-to-text**: the audio file bytes, up to 20 MB. Audio longer than 5 minutes is
  skipped by policy before upload (the audio stays on the device and keeps playing).

Sensitive-content detection runs before every upload: records that look like they contain
API keys, phone numbers, ID numbers or bank card numbers are held back until the user
confirms that specific record. That confirmation does not cover content added later.

## The official service

"Official service" mode is the same three capabilities routed through a gateway operated by
the maintainer. The client only needs the gateway URL and an access token; vendor keys stay on
the server. The gateway implementation itself is not part of this repository.

If you do not have access to the official service, configure your own providers — the app is
fully functional without any server component.

## Verified vendors

| Vendor | Chat | Embeddings | ASR | Direct from China | Notes |
|---|---|---|---|---|---|
| SiliconFlow (`api.siliconflow.cn/v1`) | Yes (Qwen) | Yes (`BAAI/bge-m3`, 1024 dim, free tier) | Yes (`FunAudioLLM/SenseVoiceSmall`, free tier) | Yes | Default choice for the official service; verified server-side |
| SenseNova (`token.sensenova.cn/v1`) | Yes (`sensenova-6.8-flash-lite`) | No | No | Yes | Reasoning models; needs a larger `max_tokens` |
| OpenAI (`api.openai.com/v1`) | Yes (`gpt-4o-mini`) | Yes (`text-embedding-3-small`, 1536 dim) | Yes (`whisper-1`) | Needs a proxy | International users |
| Anthropic (`api.anthropic.com/v1`) | Yes (Claude, choose 接口协议=Anthropic) | No | No | Needs a proxy | Chat only; no embeddings/ASR API |
| DeepSeek (`api.deepseek.com/v1`) | Yes | No | No | Yes | Chat only |
| Ollama (`http://127.0.0.1:11434/v1`) | Yes | Yes | No | Local | Leave the API key empty |
| Cloudflare Workers AI | Via the gateway | Via the gateway | Via the gateway | Yes | Slow on the free tier |

"Verified" means the endpoint shape is known and was exercised somewhere in this repository
(server-side smoke test or the app's mock-server tests). A vendor that works for chat may
still lack embeddings — that is exactly why the three cards are separate.

## Model switching and vector rebuilds

Embeddings from different models cannot be compared, even when the dimension happens to
match. ZhiCun stores the model name, the dimension and the provider URL next to every
vector, and only compares vectors from the same space.

When you change the embeddings provider or model:

1. Save the new card. Existing records keep their old vectors and drop out of semantic
   search (they still appear in keyword search).
2. The AI section shows **向量状态 → N 条记录使用旧向量模型** with a **重新向量化** button.
3. Press it. The app re-queues `EMBED` jobs for up to 500 records per press, reusing the
   normal processing pipeline (cloud switch, sensitive-content confirmation, up-link
   waiting all still apply). Records blocked by those gates are counted as not submitted.
4. Remaining records stay visible as "待重建"; press again after the queue drains.

A fresh install has nothing to rebuild. Records that are archived or deleted are skipped.

## Common errors

| What you see | Likely cause | What to do |
|---|---|---|
| 401 / 403 "请检查 API Key 或访问令牌" | Wrong or revoked key; wrong official token | Paste the key again (the field is never pre-filled) |
| 404 "找不到该接口或模型" | Base URL includes an extra path, or the model name is wrong | Enter the base URL up to `/v1`; check the exact model id |
| 402 / 429 `insufficient_quota` | Account balance exhausted | Top up or switch vendor. This is terminal: the app will not retry |
| 402 `TRIAL_EXHAUSTED` | Official-service free trial exhausted | Configure your own provider — the app stops retrying and says so |
| 429 `DAILY_QUOTA_EXCEEDED` / `SERVICE_BUSY` | Official-service daily quota reached | Retry the next day or configure your own provider |
| 429 "限流" without a quota code | Vendor rate limit | The task backs off using `Retry-After` and does not consume its retry budget |
| "API 地址无法解析" | Not a valid http(s) URL | Fix the URL |
| `INSECURE_URL` "该地址不允许明文 HTTP" | The base URL is `http://` on a host outside the loopback allow-list (LAN or public) | Use `https://`, or run the model server on the phone / emulator host |
| "模型返回的内容无法解析为 JSON" | Model ignored the JSON instruction (twice) | Try a model with better instruction following, or JSON mode support |
| "向量维度异常" | The embeddings card points at a chat endpoint | Fix the URL; valid embeddings are capped at 8192 dimensions |
| "尚未配置 AI 网关" (not configured) | The capability has no usable provider | Fill the card, or switch on the official service |

## Implementation notes (for contributors)

- `data/remote/AiGatewayHttp.kt` — one HTTP layer for both official and direct requests:
  shared timeouts, a single-concurrency gate, and one error taxonomy
  (`GatewayTransientException` = retryable, `GatewayRejectedException` = terminal,
  `GatewayRateLimitedException` = back off without consuming retries).
- `data/remote/DirectChatProvider.kt` — prompt, tolerant JSON extraction (raw / fenced /
  first-brace-to-last-brace), one retry, and a fallback that drops `response_format` when a
  vendor rejects JSON mode.
- `data/remote/DirectEmbedProvider.kt` / `DirectAsrProvider.kt` — protocol adapters.
- `data/remote/AiRouting.kt` — runtime choice between official and custom, per call.
- `data/settings/PreferencesAiProviderSettings.kt` — storage. Keys are encrypted with
  AndroidKeyStore AES/GCM; the settings screen never reads them back.
- `res/xml/network_security_config.xml` — cleartext policy. The official gateway path in
  `AiGatewayHttp.post` hard-rejects non-HTTPS in code; the custom-provider path accepts
  `http://`, and the platform's allow-list decides which hosts that actually covers
  (loopback only). `NetworkSecurityConfigTest` pins that list.
- The official gateway protocol (`/api/v1/enrich`, `/embed`, `/transcribe`) is a separate,
  incompatible contract (raw audio bytes instead of multipart, `{"title",...}` instead of
  Chat Completions). Do not mix the two paths.