# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow SemVer once the
project reaches 1.0.

## [0.1.2] - 2026-11-13

Self-hosted providers over loopback, a stable release test suite, and compiler-warning cleanup.

### Added

- **Cleartext HTTP for loopback addresses.** `res/xml/network_security_config.xml` allows
  `http://` to `localhost`, `127.0.0.1`, `::1` and `10.0.2.2` (the emulator's host alias), so a
  model server running on the device or on the emulator's host is reachable in custom-provider
  mode. Any other host still requires `https://`, and the platform rejects cleartext before the
  request leaves the app, so an API key is never sent unencrypted over a LAN or the internet.
  The allow-list is pinned by `NetworkSecurityConfigTest`.
- **`INSECURE_URL` explanation.** When the network security policy blocks a cleartext host, the
  app now reports it as `INSECURE_URL` with the allowed loopback hosts instead of a generic
  network failure.

### Changed

- **`./gradlew test` now equals `testDebugUnitTest`.** `testReleaseUnitTest` is disabled: the
  Compose/Robolectric suite depends on the debug-only `ui-test-manifest`, so a release-variant
  run failed on ~275 "Unable to resolve activity" errors that said nothing about the code.
- **Compiler-warning cleanup.** Explicit `@param:` annotation targets (`@ApplicationContext`,
  `@StringRes`, `@DrawableRes`, …) across 20+ files, `TopAppBarDefaults.topAppBarColors` instead
  of the deprecated `centerAlignedTopAppBarColors`, and Compose UI tests migrated to
  `androidx.compose.ui.test.junit4.v2.createComposeRule`.
- **`docs/ai-provider.md` and `docs/development.md`** document the cleartext policy, the
  `INSECURE_URL` error, and the release-variant test decision.

## [0.1.1] - 2026-11-13

Bilingual interface, language-aware AI output, and the open-source provider configuration
baseline.

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
- **Official trial quota handling**: `TRIAL_EXHAUSTED` (402) and `DAILY_QUOTA_EXCEEDED` /
  `SERVICE_BUSY` (429) are treated as terminal and tell the user to configure their own
  provider instead of retrying.
- **Optional release signing**: `android/keystore.properties` (gitignored) enables a signed
  `assembleRelease`; without it the build stays unsigned for contributors and CI.
- **Bilingual UI (Chinese / English).** One-tap switch in Settings → Language. On first
  launch the app follows the device language (`zh*` → Chinese, everything else → English) and
  persists the choice; afterwards it never follows the system again. Android 13+ stays in
  sync with the system "App language" page. Switching is local-only: no request, no job, no
  database migration.
- **AI output language follows the UI setting.** Processing jobs snapshot `language` when
  they are dispatched, so queued work and automatic retries keep one language even if the user
  switches afterwards. The official `/api/v1/enrich` request carries `language`; direct
  providers append a fixed instruction to their client-side prompt. Generated titles,
  summaries and tags record `output_language` (database v11), while original text, OCR,
  transcripts, web snapshots and filenames are never translated.
- **Language-aware dates and widget/notification text**: timestamp patterns, month names,
  notification actions and home-screen widget labels follow the selected language, not the
  system locale.
- **`language` contract for the gateway**: `POST /api/v1/enrich` accepts optional
  `language` (`zh` default, `en` supported; anything else is `400 INVALID_REQUEST` and never
  reaches the upstream). The fixed language instruction is appended to the upstream system
  prompt; the user text is still sent byte-for-byte in the `user` message.
- **Resource parity gate**: `scripts/release/check-i18n-res.py` verifies key parity,
  placeholders, empty values, Chinese leftovers in the English default, and the absence of a
  duplicate `values-en/`.
- **Open-source documentation**: English and Chinese README, architecture, development guide,
  AI provider guide, FAQ, privacy notice, contributing guide, code of conduct, security policy.
- **License**: Apache License 2.0 (`LICENSE`) with a `NOTICE` covering third-party attributions
  and the proprietary Google ML Kit OCR component.

### Changed

- **Resource layout**: `values/strings.xml` is now English (the default/fallback) and
  `values-zh/strings.xml` holds Chinese. All date/number formatting takes the app locale from
  the Activity configuration (`uiLocale()`), never `Locale.getDefault()`.
- **Database v11**: `processing_jobs.output_language` (nullable) and
  `derived_contents.output_language` (`NOT NULL DEFAULT 'zh'`). Old AI results are backfilled
  as Chinese and are **not** regenerated on upgrade.
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