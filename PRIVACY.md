# Privacy

ZhiCun is local-first. This document describes exactly what leaves the device, when, and
where it goes. It applies to the Android app in this repository, not to any hosted variant
someone else may operate.

## Summary

- No account, no sign-in, no analytics SDK, no advertising.
- Records, images, audio, OCR results, transcripts, summaries, tags and vectors are stored in
  the app's private storage on your device.
- Nothing is sent anywhere until you (a) switch on cloud AI processing **and** (b) configure
  an AI service. Both are off/empty by default.
- Text and audio that are sent go only to the AI endpoint you configured. If you use your own
  API key, that endpoint is the vendor you chose. If you use a gateway, it is that gateway.
- Search, OCR, page extraction and export work offline.

## What stays on the device

| Data | Where |
|---|---|
| Record text, titles, source URLs, timestamps | App-private SQLite database |
| Attachments (images, audio, video, PDFs) and web snapshots | App-private files directory |
| OCR text, transcripts, summaries, tags, vectors | Same database (derived-content table) |
| Settings, including encrypted AI keys | App-private SharedPreferences + AndroidKeyStore |
| Search history | Same database, query strings only |

The app does not write to the system gallery or to shared storage. Files that the system
share sheet or file picker hands to the app are copied into private storage; the original
stays where it was.

## What can leave the device, and when

| Data | Destination | Trigger | Where it is documented in the app |
|---|---|---|---|
| Record text (up to 4000 chars) | The AI provider you configured, for summary/tags or embeddings | Cloud AI switch on + provider configured + record not awaiting sensitive-content confirmation | Settings → Cloud AI, and the per-record AI section |
| Search query text | Same provider, for the query embedding | Same conditions, when you search | Search screen notice when semantic ranking is unavailable |
| Audio file (up to 20 MB, max 5 minutes) | Same provider, for transcription | Cloud AI on + provider configured + not awaiting confirmation | Settings → Voice transcription |
| A web page URL (and nothing else) | The website itself | You add a link (or press re-extract) | Detail page "Allow fetching this page" for suspected-sensitive records |
| Random install identifier, hashed | Official gateway only, as `X-Client-Id` | Any request to the official gateway | Settings → Privacy item 6 |

If you use a third-party vendor, that vendor's own retention and training policies apply to
the text or audio it receives. Check them before enabling cloud AI. This repository cannot
make that decision for you.

If you use the official service, the gateway stores no content: it forwards the request and
returns the result. Its optional metrics store route, duration, status, error code, provider
attempt counts, and a salted hash of the install identifier — never the text, the audio, an
account or a raw device identifier. Default retention is 180 days for request metrics and
365 days for install-day aggregates.

## Sensitive content handling

Before uploading, the app runs an on-device detector for patterns that commonly indicate
secrets: API keys, phone numbers, ID numbers, bank card numbers. A record that matches is
**not** uploaded until you confirm that record. Confirmation is bound to the exact content
version and the content parts you saw; adding an OCR block or a transcript later invalidates
it and the app asks again.

Web fetching is confirmed separately, because it contacts the website rather than an AI
service, and it is allowed while cloud AI is off.

This detector is pattern-based. It can miss things, and it is not a guarantee. Do not store
content you would not want a provider to see, and check the provider's policies.

## Permissions and why they exist

| Permission | Purpose |
|---|---|
| Overlay (`SYSTEM_ALERT_WINDOW`) | Floating capture bubble |
| Microphone | Voice recording (recording only starts on an explicit action) |
| Notifications | Foreground-service notification for the bubble and long-running jobs |
| Foreground service (media projection, microphone) | Floating bubble + recording keep-alive |
| Network | AI providers and web page extraction |
| Query installed packages | Not used for analytics; only to resolve share targets / feedback mail app |

The app does not request contacts, SMS, location, calendar, camera (photos are chosen through
the system picker) or "all files access".

## Clipboard

Clipboard capture reads text only when you tap the bubble with clipboard capture enabled (or
use the explicit "Save clipboard" action). On Android 12+ the system shows its own confirmation. The
app never reads the clipboard in the background.

## Your controls

- **Cloud AI switch**: turning it off stops new uploads immediately. Existing local data and
  results are unaffected.
- **Per-record confirmation**: for suspected-sensitive records, upload happens only after an
  explicit confirmation for the current content.
- **Export**: Settings → Data → Export all records creates a ZIP you can move anywhere.
- **Delete**: records can be soft-deleted (archived/hidden) and then permanently removed with
  "Clean up deleted records", which also deletes attachment files.
- **Uninstall**: removes the app's private directory, including all records and attachments.
  Export first if you want to keep them.

## Data from other apps

When you share content into ZhiCun, the sharing app decides what it sends. ZhiCun stores what
it receives and records which app shared it (package/label) only for display in the detail
page.

## Children

The app is not directed at children and collects no personal information by itself.

## Changes

Material changes to these data flows will be noted in `CHANGELOG.md` and reflected in the
in-app privacy section before a release.

## Contact

Open a GitHub issue for questions that do not involve personal data. For a privacy concern
that involves your data, use the private reporting route in `SECURITY.md`.