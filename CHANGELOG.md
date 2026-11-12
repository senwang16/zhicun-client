# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow SemVer once the
project reaches 1.0.

## [Unreleased]

Open-source readiness work. No release artifacts yet.

### Added

- **Three independent AI providers.** Summary/tags, embeddings and speech-to-text are now
  configured separately (address, key, model), so different vendors can be mixed. Both the
  official gateway protocol and direct OpenAI-compatible endpoints are supported, chosen at
  runtime per call.
- **Settings UI for AI configuration.** Official service card (address + token) and three
  provider cards with per-card Save, configuration status, and vector-rebuild status.
- **Direct provider implementations**: OpenAI-compatible `/chat/completions` (with client-side
  prompt, JSON extraction that tolerates Markdown fences and prose, JSON-mode fallback, one
  retry), Anthropic Messages (`/messages`, `x-api-key` + `anthropic-version`, text-block
  concatenation), `/embeddings` (with dimension guard), and multipart
  `/audio/transcriptions`.
- **Vector identity tracking** (`embedding_meta`, database v10): model, dimension, provider
  URL and timestamp per stored vector. Search and related-records only compare vectors from
  the same space; legacy vectors without identity are treated as wildcards.
- **Batch vector rebuild** (`ReEmbedAllUseCase`): requeues `EMBED` jobs for records whose
  vectors are incompatible, reusing the existing pipeline and privacy gates.
- **Error-code coverage** for OpenAI-style and domestic vendor envelopes, plus explicit
  handling of 402 and quota-exhausted 429 responses (terminal) versus ordinary rate limits.
- **Open-source documentation**: English and Chinese README, architecture, development guide,
  AI provider guide, FAQ, privacy notice, contributing guide, code of conduct, security policy.
- **License**: Apache License 2.0 (`LICENSE`) with a `NOTICE` covering third-party attributions
  and the proprietary Google ML Kit OCR component.

### Changed

- `AiGatewaySettings.isConfigured` now means "at least one cloud capability is usable", not
  "the official gateway is configured". Without this a fully configured custom setup would be
  blocked by the cloud upload gate.
- Provider HTTP error messages no longer say "网关" unconditionally; they mention the API key
  or token depending on the failure, since direct mode has no gateway.
- Token encryption is shared between the official token and all provider API keys.

### Fixed

- Custom providers were previously unreachable: the cloud path required an official gateway.
- Per-capability availability is now reflected in Settings ("未配置" instead of a misleading
  "当前可用" for transcription).
- Re-embedding after a model change no longer requires deleting records or reinstalling.

### Migration

- Database v9 → v10 creates `embedding_meta` and backfills existing vectors with their model
  name (`0` dimension, empty provider = unknown). Existing vectors stay searchable.

## [0.1.0]

Initial internal version: capture (share, bubble, widget, voice), inbox/organize, OCR, web
extraction, keyword + semantic search, collections, export, floating bubble, onboarding.