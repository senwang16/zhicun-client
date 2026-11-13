# Architecture

This document describes the Android client and how it talks to AI providers.

The official gateway (an optional hosted service) is operated by the maintainer and its
source is **not** part of this repository. The client does not depend on it: with the
"custom provider" mode it talks directly to any OpenAI-compatible endpoint.

## 1. Repository layout

```
android/                     Android app (single Gradle module `app`)
  app/src/main/kotlin/com/zhicun/app/
    domain/                  pure Kotlin: models, contracts, policies, use cases
      ai/                    ContentEnricher / Embedder / SpeechTranscriber + provider config
      model/                 Record, Asset, DerivedContent, ProcessingJob, ...
      repository/            repository interfaces
      settings/              AiSettings, CloudUploadGate, FloatingBubbleSettings, ...
      usecase/               one class per user-visible operation
      search/                query parsing, hybrid ranking, vector math
      worker/                ProcessingScheduler contract, job targets
    data/                    Android implementations
      local/                 Room entities, DAOs, migrations, FTS callback
      remote/                HTTP providers (official gateway + direct OpenAI-compatible)
      repository/            Room/HTTP-backed repositories
      settings/              SharedPreferences implementations (+ KeyStore encryption)
      worker/                WorkManager workers (OCR, web, summarize, embed, transcribe)
      export/, file/, web/, ocr/, audio/, platform/
    presentation/            Jetpack Compose screens + ViewModels
    service/                 floating bubble service, share receiver, capture activities
    di/                      Hilt modules
  app/src/test/kotlin/       JVM + Robolectric tests, fakes in `fake/`
  app/schemas/               exported Room schemas (one JSON per version)

docs/                        architecture, development, provider, FAQ, privacy
```

The dependency direction is one-way: `presentation → domain ← data`, `di` wires them.
`domain` has no Android imports, which is what allows most policy tests to run on the JVM
without Robolectric.

## 2. Data model

Room is the single source of truth (`data/local/AppDatabase.kt`, currently **v11**).

| Table | Purpose | Notes |
|---|---|---|
| `records` | One row per captured item | Soft delete (`is_deleted`), archive (`archived_at`), favorites/pin, sensitivity status, `record_revision` for optimistic edits, per-revision cloud/web confirmations |
| `assets` | Attachment files | Private storage paths, MIME type, size, save status, original file name, order index |
| `derived_contents` | Rebuildable AI/local derivatives | `(record_id, content_type, asset_id)` unique; `processor_version` records which pipeline produced it; `output_language` on generated types (`TITLE`/`SUMMARY`/`TAGS`, v11); vectors are stored here as Base64 float32 |
| `processing_jobs` | One row per `(record_id, job_type)` | `QUEUED / RUNNING / DONE / FAILED / CANCELLED`, attempts, wait rounds, retry timing, `output_language` dispatch snapshot (v11) |
| `tags`, `record_tag_cross_ref` | User and AI tags | AI tags are replaced on re-processing; user tags are never silently removed |
| `collection_members` | Records grouped into a collection | Composite key, position, snapshot fingerprint |
| `search_history` | Recent queries, on-device only | Deduplicated, capped |
| `embedding_meta` | Identity of each stored vector (v10) | Model, dimension, provider URL, timestamp; the vector body stays in `derived_contents` |

Full-text search uses an FTS4 virtual table `record_fts`, created and kept in sync by
`FtsSyncCallback` (Android's bundled SQLite has no FTS5).

**Migrations are mandatory.** `fallbackToDestructiveMigration()` is forbidden: all user data
lives only on the device and there is no cloud backup. Every schema bump adds an entry to
`data/local/migration/Migrations.kt`, registers it in `AppDatabaseFactory`, exports a new
schema JSON, and adds a migration test that creates the old schema from the exported JSON.

## 3. Capture and processing

```
share sheet / bubble / widget / manual / voice
        │
        ▼
BuildRecordInputUseCase → RecordRepository.save() → ScheduleProcessingUseCase
        │                                                   │
        │                                     ProcessingScheduler (WorkManager)
        │                                                   │
        ▼                                                   ▼
 Room: records + assets                    WWorker (per job type): OCR, WEB_EXTRACT,
                                           TRANSCRIBE, SUMMARIZE, EMBED
                                                        │
                                                        ▼
                                           ProcessorResult → ProcessingJobExecutor
                                           (success / retry with backoff / wait upstream
                                            / blocked / permanent failure)
```

Key rules:

- `(recordId, jobType)` is unique. Re-processing overwrites the derived row, never appends.
- A job that lacks upstream text (e.g. summary before OCR finished) returns
  `WaitUpstream` — it does not consume a retry budget and does not turn the record red.
- "No text at all" is a success with an empty derivative, not a failure. Users see
  "没有可摘要的文字内容" instead of a permanent error they cannot fix.
- `record.processingStatus` is aggregated from job rows, not written optimistically by each
  worker.

## 4. Privacy gates

Every outbound path goes through one policy object (`domain/settings/CloudUploadGate.kt`):

1. the global cloud switch must be on;
2. at least one AI provider must be configured (official or custom);
3. records flagged as possibly sensitive (API keys, phone/ID/bank card patterns detected
   on-device) must be confirmed by the user **for the current content revision and content
   parts**. Adding new parts (an OCR block, a transcript) invalidates that confirmation.

Web page fetching is a separate gate: it contacts the site, not the AI provider, so it is
allowed while cloud AI is off, but a suspected-sensitive record still needs explicit
per-record confirmation before the request.

The checks exist in three places on purpose: scheduling (do not enqueue), execution
(re-check with a fresh record right before the request, guarding TOCTOU), and the UI
(explain why nothing was uploaded). All three call the same functions.

## 5. Search

```
query ──► FTS4 keyword search (records, OCR, transcripts, summaries, titles, URLs)
      └─► Embedder.embedQuery (cached, short timeout) ──► cosine over stored vectors
                                                        (same model + same provider only)
      └─► HybridRanker: merge, boost exact title/tag hits, favorites, pinned, recency
```

- Semantic search is an enhancement. Any failure (no provider, timeout, empty vector)
  degrades to keyword-only results with an honest notice.
- Vectors carry model + provider identity. Vectors from another embedding space are excluded
  (`embedding_meta`), so changing models cannot silently produce noise, and the app offers a
  batch rebuild (`ReEmbedAllUseCase`, which requeues `EMBED` jobs rather than duplicating the
  embedding pipeline).
- Similarity thresholds are intentionally different for search (recall-oriented) and related
  records (precision-oriented). Both are constants in `VectorMath` /
  `GetRelatedRecordsUseCase`.

## 6. AI provider layer

Three independent capabilities, each backed by two implementations and one router:

```
ContentEnricher  ◄─ RoutingContentEnricher ─► RemoteContentEnricher (official gateway)
                                            └► DirectChatProvider   (OpenAI /chat/completions
                                                                     or Anthropic /messages)

Embedder         ◄─ RoutingEmbedder ────────► RemoteEmbedder        (/api/v1/embed)
                                            └► DirectEmbedProvider (/embeddings)

SpeechTranscriber ◄─ RoutingSpeechTranscriber ► RemoteSpeechTranscriber (raw audio bytes)
                                             └► DirectAsrProvider (multipart)
```

Routing rule (per call, so settings changes apply immediately):

```
official selected && official configured  → official
else the capability's own config exists    → custom
else                                       → official (throws "not configured")
```

There is deliberately no silent fallback in the last case: using a different provider than
the one the user selected would mean sending their content with a key they did not choose
for that purpose.

`AiGatewayHttp` is the single HTTP layer for both paths: shared timeouts (120 s long path,
6 s search path), one concurrency gate, redirects disabled, and one error taxonomy:

| Exception | Meaning | Retry behaviour |
|---|---|---|
| `GatewayNotConfiguredException` | no usable provider | terminal, user-fixable |
| `GatewayRejectedException` | 4xx, auth failure, quota exhausted | terminal |
| `GatewayRateLimitedException` | 429 without a quota code | back off by `Retry-After`, no retry budget consumed |
| `GatewayTransientException` (an `IOException`) | 5xx, timeout, broken response | retried with backoff |

Keys and tokens are stored encrypted (AndroidKeyStore AES/GCM via `TokenCipher`) and are
never read back into the UI or logged.

### 6.1 UI language and AI output language

The app has two language flows that share one setting but never trigger each other:

```
Settings → Language (中文 / English)
        │  writes language_preference/ui_language (no network, no job)
        ├─► UI resources: attachBaseContext (MainActivity, floating service,
        │   transparent activities) + widget labels; Android 13+ LocaleManager sync
        └─► AI output: read once at dispatch, snapshotted into
            processing_jobs.output_language
                └─► SummarizeWorker → ContentEnricher.enrich(text, language)
                        ├─ official: {"text","language"} → gateway prompt
                        └─ direct:   fixed instruction appended to system prompt
```

Rules that keep the two flows honest:

- The first launch derives the language from the device (`zh*` → Chinese, anything else →
  English) and **persists it immediately**; afterwards the system language is ignored.
- Switching language is **local-only**: no request, no worker, no re-processing. Existing
  results stay in the language they were generated in (`derived_contents.output_language`).
- `SummarizeWorker` never reads the language preference. It reads the job snapshot, so an
  automatic retry of a queued job cannot silently change language mid-way.
- Manual retry / confirmation / re-enabling cloud AI take the **current** language and
  overwrite the snapshot, which is the only path that produces a new-language result.
- Original content is not language-tagged: OCR text, transcripts, web page text, snapshots
  and file names are kept byte-for-byte. Only `TITLE` / `SUMMARY` / `TAGS` carry
  `output_language`.

## 7. Web extraction

`OkHttpWebPageLoader` + `HtmlContentExtractor` fetch and extract the main article text,
store a snapshot, and inline images for offline viewing. Guard rails: HTTPS-only, DNS
pinning to prevent resolution rebinding, per-hop redirect validation, content-type and size
limits. See `SafeUrlChecker`, `PinnedDns`, `WebPageLoader`.

## 8. Presentation

- Compose + ViewModel + `StateFlow`, one screen package per feature.
- UI state is derived from real values, never duplicated ("show X because Y is true" computed
  on the fly), which keeps tests able to assert truth tables on plain data classes.
- Test tags are centralized (`SettingsTestTags`, similar objects per screen); Compose tests run
  on Robolectric, no device required.

## 9. Where to add things

| You want to... | Touch these |
|---|---|
| Support another AI vendor | Add nothing if it speaks OpenAI-compatible or Anthropic Messages (users select the protocol). A third protocol needs a new branch in `DirectChatProvider`/`AiGatewayHttp` + tests. |
| Add a processing step | New `JobType` + worker + `ProcessingJobTargets` entry + DAO fields if persisted + migration if schema changes |
| Change the schema | Entity, DAO, migration, `AppDatabase` version, `app/schemas`, migration test |
| Add a setting | `domain/.../Settings` interface, `Preferences*` impl, Hilt binding, `SettingsUiState`, ViewModel, Compose section, string resources, tests |