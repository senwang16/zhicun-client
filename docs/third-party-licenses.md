# Third-party dependencies and licenses

> Status: **inventory for information, not legal advice.** The project license was decided on
> 2026-11-12: **Apache License 2.0** (`LICENSE`). Third-party attributions and the one
> proprietary exception (Google ML Kit) are listed in `NOTICE` at the repository root.
> The commercial-use conclusion for ML Kit is the maintainer's decision and is not implied by
> this inventory.

## Android app (`android/gradle/libs.versions.toml`)

| Dependency | Version | License | Notes |
|---|---|---|---|
| AndroidX Core / Lifecycle / Activity / Navigation | see catalog | Apache-2.0 | |
| Jetpack Compose (BOM, ui, material3) | 2026.06.01 | Apache-2.0 | |
| Hilt / Dagger | 2.58 | Apache-2.0 | annotation processor runs at build time only |
| Room | 2.8.5 | Apache-2.0 | |
| WorkManager | 2.11.2 | Apache-2.0 | |
| androidx.hilt (work, compiler, navigation) | 1.3.0 | Apache-2.0 | |
| Coil 3 | 3.5.0 | Apache-2.0 | image loading |
| OkHttp | 4.12.0 | Apache-2.0 | HTTP |
| Retrofit + kotlinx-serialization converter | 2.12.0 | Apache-2.0 | declared; direct HTTP is used for AI |
| kotlinx.serialization | 1.9.0 | Apache-2.0 | |
| jsoup | 1.23.2 | MIT | HTML extraction |
| kotlinc / Kotlin stdlib | 2.2.21 | Apache-2.0 | |
| **ML Kit text-recognition-chinese** | 16.0.1 | **Proprietary (Google ML Kit Terms of Service)** | Not an open-source license. Bundled model ships inside the APK; redistribution is governed by Google's ML Kit terms, which is why the plan calls for a `NOTICE` entry before release. |
| kizitonwose calendar-compose | 2.10.1 | MIT | calendar sheet |
| JUnit 4 | 4.13.2 | EPL-1.0 | test only |
| kotlinx-coroutines-test | 1.10.2 | Apache-2.0 | test only |
| Turbine | 1.2.1 | Apache-2.0 | test only |
| Robolectric | 4.17 | Apache-2.0 | test only |
| OkHttp MockWebServer | 4.12.0 | Apache-2.0 | test only |

## Gateway (not distributed with this repository)

The official gateway is maintained privately and is not part of the public tree, so its
Node.js dependencies are not distributed to users. For reference, its build tooling uses
wrangler / workerd / TypeScript / Vitest / MSW (Apache-2.0 or MIT). That tooling also pulls
a few weak-copyleft binaries transitively (`@img/sharp-libvips-*` LGPL-3.0, `lightningcss`
MPL-2.0) inside `node_modules`; they are neither bundled into the deployed Worker nor shipped
to app users.

## Obligations to keep in mind

1. **Attribution**: Apache-2.0 dependencies require preserving `NOTICE` files where present.
   `NOTICE` now lists the components shipped in the APK. Apache-2.0 §4(d) requires any
   redistributed build to carry a readable copy of those attribution notices (a file next to
   the APK, or an in-app notices screen).
2. **ML Kit**: the Chinese OCR model is not open source. `NOTICE` declares it as a proprietary
   Google component governed by the Google ML Kit Terms of Service, explicitly outside the
   project's Apache-2.0 grant. Whether shipping it in your own build is acceptable for your
   distribution is still a maintainer/business decision, not a code question.
3. **Project license (decided)**: Apache-2.0 composes cleanly with all of the above except
   ML Kit, which keeps its own terms. Apache-2.0 is compatible with AndroidX/Kotlin/OkHttp
   (also Apache-2.0), jsoup and kizitonwose (MIT), and JUnit (EPL-1.0, test-only).
4. **No GPL/AGPL** dependency is used in the app. The gateway's **build tooling** pulls a few
   weak-copyleft binaries transitively — `@img/sharp-libvips-*` (LGPL-3.0-or-later),
   `lightningcss` (MPL-2.0) — inside `node_modules`; they are used to build/serve the Worker
   locally and are neither bundled into the deployed Worker nor part of this repository's
   source. They do not change the license of this project, but do not vendor them into the
   repo or ship them in a product without re-checking.

## To do before the first public release

- [x] Ship a `NOTICE` file with attribution and the ML Kit statement.
- [ ] Generate a complete machine-readable inventory (`./gradlew :app:dependencies`) and diff
      it against this table.
- [ ] Decide whether an in-app third-party notices screen is needed in addition to `NOTICE`.
- [ ] Record the ML Kit commercial conclusion — this is the maintainer's decision. Shipping the
      bundled OCR model is permitted by Google's ML Kit terms, but those terms are not the
      Apache-2.0 grant and should be reviewed before commercial distribution.