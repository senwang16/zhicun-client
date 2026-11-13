# Development guide

How to build, test and extend ZhiCun. Product and architecture context lives in
`docs/architecture.md`; AI provider specifics in `docs/ai-provider.md`.

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17+ (21 tested) | AGP 8.13 requires 17+ |
| Android SDK | platform 36, build-tools 36.0.0 | `compileSdk = 36` is a hard ceiling in this project |
| Gradle | wrapper 8.14.3 | use `./gradlew`, not a system Gradle |
| Node.js | 20+ | optional; only for the maintainer's private gateway tooling, not needed to build the app |
| git | any recent | — |

Point Gradle at the SDK by creating `android/local.properties` (gitignored):

```properties
sdk.dir=/path/to/Android/Sdk
```

or export `ANDROID_HOME`.

## 2. Build and test

```bash
cd android
./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # every JVM + Robolectric test
./gradlew :app:compileDebugKotlin     # fast syntax/type check while iterating

# single class / single test
./gradlew :app:testDebugUnitTest --tests 'com.zhicun.app.data.remote.*'
```

On Windows use `gradlew.bat`. Robolectric downloads `android-all` jars on first run; the build
points it at the Aliyun Maven mirror (see `app/build.gradle.kts`) because the default is slow
from China. If you are outside China and the mirror is slower, change
`robolectric.dependency.repo.url` locally — do not commit that change.

### Optional: bake a gateway into a debug build

```bash
./gradlew :app:assembleDebug -PaiGatewayUrl=https://your-gateway -PaiGatewayToken=...
```

or put `aiGatewayUrl` / `aiGatewayToken` in `android/local.properties`. This is for local
integration only. **Never** inject tokens into a release build or CI.

## 3. Conventions

- **One-way dependencies**: `presentation → domain ← data`. `domain/` must stay free of
  Android imports so policy tests run on the JVM.
- **Interfaces in `domain/`, implementations in `data/`.** A new capability starts with a
  domain contract, not with an Android class.
- **Comments explain why, not what.** The code base is comment-heavy on purpose: most comments
  record a decision and the failure mode it prevents. Keep that standard; a comment that
  restates the code is noise.
- **User-visible text is a string resource.** `values/strings.xml` is the **English default**
  (also the fallback), `values-zh/strings.xml` is Chinese. Do not hardcode user-visible text in
  Composables, ViewModels, Services, notifications or Workers; add the key to both files and run
  `python scripts/release/check-i18n-res.py`. Language names are the one exception: they are
  written in their own language (`中文`, `English`).
- **Never read `Locale.getDefault()` for formatting.** Use `uiLocale()` in Composables or take
  a `Locale` parameter in pure functions; the app language may differ from the system.
- **AI output language is explicit.** `ContentEnricher.enrich(text, language)` receives the
  language snapshotted on the job at dispatch time; implementations must not read preferences or
  infer language from the input. Generated results record `output_language`; original content
  (OCR, transcripts, page text) must never be translated.
- **KDoc is written in Chinese** for historical reasons. Public docs (README, `docs/`) are
  English with a Chinese mirror for the README.
- Kotlin style: 4-space indent, no wildcard imports, expression bodies for one-liners,
  immutable data classes, `StateFlow` for UI state.
- Tests: JVM for domain, Robolectric for Room/Compose/SharedPreferences, MockWebServer for
  HTTP. Use the fakes in `app/src/test/kotlin/com/zhicun/app/fake/` rather than mock
  frameworks; a fake that behaves like production is what keeps tests honest.
- Test names are sentences (`Upsert_emptyVector_writesNothing`), and each test asserts one
  behaviour that could plausibly regress.

## 4. Changing the database schema

1. Bump `@Database(version = N)` in `AppDatabase.kt` and add the entity/column.
2. Write `MIGRATION_(N-1)_N` in `migration/Migrations.kt`; add it to `ALL_MIGRATIONS`.
3. Add a migration test in `MigrationTest.kt`: build the old schema from the **exported JSON**
   (never hand-write DDL), seed a realistic row, open the DB with `AppDatabaseFactory`, and
   assert data survived plus the new structure validates.
4. Run the build so KSP exports `app/schemas/.../N.json`; commit it.
5. `fallbackToDestructiveMigration()` is forbidden — losing user data on upgrade is worse
   than crashing with a clear log.

Column defaults matter: Room validates the post-migration schema against the entity,
including `NOT NULL` and `DEFAULT`. A mismatch only reproduces on devices upgrading from an
older version, which is exactly why the test exists.

## 5. Adding a processing step

1. Add a `JobType` and its mapping in `ProcessingJobTargets`.
2. Implement a worker extending `BaseProcessingWorker`; return a `ProcessorResult`
   (`Success`, `Retry`, `WaitUpstream`, `Blocked`, `Permanent`) rather than throwing for
   expected outcomes.
3. Gate the worker with `CloudUploadGate` if the step sends content off-device, and
   re-check with `ProcessingJobExecutor.recheckBeforeUpload` immediately before the request.
4. Register it in the WorkManager scheduler mapping.
5. Add tests for: happy path, upstream-not-ready, blocked (cloud off / sensitive),
   idempotent re-run.

## 6. Adding an AI provider

Providers that speak the OpenAI-compatible protocols need **no code**: users fill in URL, key
and model in Settings. New protocols need a new implementation of the relevant domain
interface plus a routing entry in `AiModule`:

1. Implement `ContentEnricher`, `Embedder` and/or `SpeechTranscriber`.
2. Throw the existing exception types (`GatewayNotConfigured/Rejected/RateLimited/Transient`)
   so the job executor's retry classification keeps working.
3. Wire it into `AiRoutingModule` and extend `AiProviderSettings` if it needs new config.
4. Cover request shape, response parsing, and each error mapping with MockWebServer tests.

## 7. Manual acceptance checklist (device)

Automated tests cannot cover the device's system integrations. Run this before a release:

- [ ] Share text, a link, an image and a PDF into the app from another app; all appear in Inbox
- [ ] Clipboard bubble captures text; duplicate capture is rejected
- [ ] Floating bubble drag / collapse / restore after reboot; long-press recording works
- [ ] Voice note records, plays back, and transcribes (or reports a clear reason why not)
- [ ] OCR on a photo with Chinese text; multi-image record keeps segments separate
- [ ] Web link extraction; snapshot opens offline; blocked page reports why
- [ ] Summary + tags generated; empty text shows "没有可摘要的文字内容" rather than a failure
- [ ] AI provider: official mode with a gateway; custom mode with three different vendors
- [ ] Sensitive record (a fake API key in the text) is held back; confirm upload works
- [ ] Embedding model change shows "待重建 N 条"; rebuild drains and search returns semantic hits
- [ ] Search: keyword, semantic, tag filter, archived search, search history
- [ ] Collections: create, open, regenerate summary, stale-source banner
- [ ] Export ZIP opens on a desktop; attachments present; cleanup frees space
- [ ] Upgrade install over the previous APK keeps all records (migration)
- [ ] Cloud off: no network requests at all for AI paths (verify via proxy or airplane mode)

## 8. Release builds

The repository has **no** signing key: a fresh clone produces an unsigned release APK. To sign
locally:

1. create a dedicated release keystore **outside the repository** (never commit it):
   ```bash
   keytool -genkeypair -v -keystore /path/to/zhicun-release.jks -alias zhicun \
     -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=ZhiCun"
   ```
2. copy `android/keystore.properties.example` to `android/keystore.properties` and fill in the
   absolute `storeFile`, `storePassword`, `keyAlias`, `keyPassword` (both files are gitignored);
3. `./gradlew :app:assembleRelease` — the APK is signed when `keystore.properties` exists, and
   stays unsigned when it does not (contributors and CI);
4. verify the signature and record the certificate fingerprint:
   ```bash
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```
5. confirm the APK embeds **no** gateway URL/token (check the generated `BuildConfig.java`, or
   `strings` the dex);
6. keep R8/obfuscation off until a full device pass has been done with it on
   (see the comment in `app/build.gradle.kts`);
7. ship `LICENSE` and `NOTICE` alongside any binary distribution (Apache-2.0 §4(a) and §4(d));
8. bump `versionCode`/`versionName` and update `CHANGELOG.md`.

**Back up the keystore and its passwords.** If they are lost, existing users cannot install an
update over the old app; they would have to uninstall and lose their local data.

## 9. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `SDK location not found` | create `android/local.properties` with `sdk.dir=...` |
| `Failed to find Build Tools revision 36.0.0` | install build-tools 36 via SDK Manager |
| Compose compile errors after a dependency bump | versions are pinned to a compileSdk 36 ceiling; read the header of `gradle/libs.versions.toml` before upgrading anything |
| Robolectric download stalls | confirm the Aliyun mirror system property in `app/build.gradle.kts`; first run downloads ~100 MB per SDK |
| CI: `Plugin [id: 'com.google.devtools.ksp'] was not found`, while `curl` returns 200 | The Aliyun mirror answered **502** to the hosted runner, and Gradle treats a 5xx as fatal for that artifact instead of falling through. `settings.gradle.kts` now skips the mirrors when `CI=true`; locally they are still used. |
| `OutOfMemoryError` during tests | increase `org.gradle.jvmargs` in `gradle.properties`; delete `~/.gradle/caches/modules-2` only as a last resort |
| AI requests fail with 401/402/404 | check the provider card: key, account balance, base URL (up to `/v1`) and model name — see `docs/ai-provider.md` |