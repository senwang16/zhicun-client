# Contributing to ZhiCun

ZhiCun / 智存 is an Android-first personal knowledge capture and AI organization app. The app lives in
`android/` (Kotlin, Jetpack Compose, Hilt, Room, WorkManager, ML Kit OCR, OkHttp). The official AI
gateway is a separate, private service; this repository does not contain its source.

The project is maintained by one individual developer, so review and response times are best effort.
Small, well-scoped contributions are much easier to review, test, and merge than large ones.

## Before you open a pull request

- Open an issue first for anything larger than a typo, a documentation fix, or an obvious bug fix.
  Describe the problem and the intended change so the approach can be agreed on before you write code.
- Security problems do not belong in a public issue. Follow `SECURITY.md` and report privately.
- Never commit credentials or user data: no API keys or tokens, `android/local.properties`, captured
  text, images, audio, or logs that contain any of these. If you commit a secret by accident, revoke
  and rotate it, and say so in the PR.
- ZhiCun is licensed under the Apache License 2.0 (`LICENSE`). By submitting a pull request you
  agree that your contribution is provided under that same license, without any additional terms.
  No contributor license agreement is required. Do not add per-file license headers; the repository
  license and `NOTICE` cover the project. Do not add a different license, license badge, or a new
  `LICENSE` file in a PR.

## Repository layout

| Path | Contents |
| --- | --- |
| `android/` | Kotlin app. Gradle wrapper: `android/gradlew.bat` on Windows, `android/gradlew` elsewhere. |
| `docs/` | Architecture, development, provider, FAQ and privacy notes. Update these when behavior or setup changes. |

The official gateway is operated privately by the maintainer and its source is not in this
repository. Client contributions must not depend on it: everything must work with a
user-configured OpenAI-compatible provider.

## Build and test locally

Android (requires an Android SDK with platform 36; `compileSdk 36`, `minSdk 26`):

```sh
cd android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

On Windows use `gradlew.bat` in place of `./gradlew`:

```bat
cd android
gradlew.bat :app:assembleDebug
gradlew.bat :app:testDebugUnitTest
```

Server-side code is not part of this repository, so there is nothing to build or test on the
server side here.

### Local configuration

- Android reads an optional gateway URL and token from `android/local.properties` (gitignored) or from
  the Gradle properties `-PaiGatewayUrl=...` and `-PaiGatewayToken=...`. Both are optional: the app is
  usable without a gateway, OCR runs on-device, and custom providers are configured in the app.
- Never commit real keys, tokens, Cloudflare account IDs, D1 database IDs, `local.properties`,
  `.dev.vars`, captured content, or logs that contain any of these.

## Branch and pull request expectations

- Branch from the current default branch and open the PR against it. Keep the branch focused on one
  concern; if a fix needs a refactor, split the refactor into a separate PR that lands first.
- Include tests for behavior changes. A bug fix should normally come with a test that fails before the
  fix and passes after it. Existing test suites live in `android/`; extend them rather than adding a
  new framework.
- No drive-by refactors, mass reformatting, unrelated dependency upgrades, or renames of public APIs
  without an issue that agrees on them.
- Kotlin code style: 4-space indentation, no wildcard imports. Match the formatting of the surrounding
  file, and keep imports explicit and ordered.
- Explain the change in the PR description: what problem it solves, how you tested it, and any privacy
  impact. If the change causes data to leave the device (a new endpoint, header, or field), say exactly
  what is sent, where, and under which setting.
- Do not commit build outputs, generated files, or editor-specific configuration that other
  contributors do not need.

## Commit messages

Use a short imperative subject line, for example `Fix OCR retry on empty image` or
`Add gateway probe timeout`. Keep the subject under about 72 characters, capitalized, with no trailing
period. Use the body to explain why the change is needed and any tradeoffs; do not restate the diff.

## Reporting bugs

Open a GitHub issue and include:

- App version (from Android's app info screen for the installed build, or the APK you installed).
- Android version and device model.
- Whether cloud AI is enabled, and which provider kind (official gateway or your own
  OpenAI-compatible provider). Never paste the key or token.
- Steps to reproduce, expected behavior, and actual behavior.
- A logcat excerpt if it is relevant, with keys, tokens, `X-Client-Id` values, and user content
  redacted.

Feature requests are welcome too. Describe the problem you are trying to solve and what you have tried,
rather than only the UI change you want.

## What the maintainer reviews for

Every PR is reviewed with these concerns in mind; address them in the description rather than waiting
for review comments.

- Privacy regressions: user content leaving private app storage by default, OCR moving off-device,
  new analytics or identifiers, or `X-Client-Id` being used for anything beyond rate-limit bucketing on
  the official gateway. The gateway should store only a salted hash for aggregate metrics.
- Migration safety: Room schema changes need a working migration path and a test; never ship a
  destructive migration that silently drops captures.
- Error handling: network failures, timeouts, provider errors, malformed responses, and quota or rate
  limit errors must degrade gracefully and never lose locally captured content.
- Secrets handling: keys and tokens stay in AndroidKeyStore-backed storage (see
  `data/settings/KeystoreTokenCipher.kt`) and never appear in source, logs, or test fixtures.
- Provider protocol code: `data/remote/DirectChatProvider.kt`, `DirectEmbedProvider.kt` and
  `DirectAsrProvider.kt` are security-sensitive (they carry user keys and content) and receive
  closer review.

## AI-assisted contributions

AI-assisted contributions are welcome. You are responsible for understanding every line you submit and
for testing it yourself; a contributor who cannot explain why a change works should not submit it.
Review generated code for secrets, copied code without attribution, privacy regressions, and invented
APIs before opening the PR. Mention substantial AI assistance in the PR description; it helps the
review, and it is not a reason for rejection.

## Questions

Use GitHub Issues for questions about contributing. Keep discussions in the open so the answers are
useful to the next contributor. Security reports remain the exception; see `SECURITY.md`.