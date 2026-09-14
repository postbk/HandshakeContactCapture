# Handshake Contact Capture

## Purpose

A native Kotlin Android app for remembering and acting on connections made at trade shows. Photograph a business card or company fact sheet, review extracted points of contact (POCs), research the company, and optionally record a short personal voice memo. Keep every encounter in a searchable event database and export selected records to Google Contacts or CSV.

This document is the implementation brief for future ChatGPT/Codex sessions. Companion instructions are in [AGENTS.md](AGENTS.md).

## Status as of September 13, 2026

The app provides a local event notebook plus photo extraction and company research. Create events, save/edit people or company-only connections, search an event, and track follow-ups. Scan/import up to four photos per batch, review extracted people before saving, and request sourced company research from a saved connection. The user explicitly chose to enter their own OpenAI key in the app with encrypted device storage. Conversation summaries now include private audio capture, OpenAI transcription, reviewed text analysis, and suggestions that can be added to notes/follow-ups. CSV and reviewed Google-account Contacts export are implemented; real-account/cloud-sync acceptance remains.

Database version 4 adds contact/account export history; version 3 added ConversationSummary to the version-2 Capture/Research/Event/Contact/Encounter database. Migrations from versions 1 and 2 are tested. Captures retain normalized source photos, original structured extraction JSON, warnings, model, and processing state. Reviewed candidates use deterministic IDs to prevent duplicate saves and retain a capture reference and printed claims. Multiple extracted phones/emails/addresses/websites are retained as editable newline-separated values; the original arrays remain in the extraction JSON. Normalized companies/contact-method tables, cross-batch duplicate matching, linking an existing contact from the UI, event deletion, and cross-event search remain future work. Never destructively recreate user databases.

Implementation files: MainActivity.kt and ui/AiScreens.kt (Compose screens), HandshakeViewModel.kt (state and asynchronous writes), data/HandshakeDatabase.kt (Room schema/DAO/migration), capture/PhotoStorage.kt (private image handling), and ai/ (encrypted settings, API contracts/client, WorkManager jobs). Versioned Room schemas are exported to app/schemas. Automatic cloud backup and device transfer of database/files/preferences are excluded.

Validation: debug assembly and unit tests passed. All 22 routine connected tests passed (two legacy Android provider probes skipped) on a TB336FU running Android 16, including version-1 migration, photo orientation/metadata removal, encrypted-key storage/removal, extraction validation, source parsing, review corrections, stale-result/deletion handling, and research confirmation. Lint completed without errors; advisory warnings remain. Tests use synthetic images and fake API responses. Paid live OpenAI calls, external camera-app interaction, and a full offline/process-death/retry rehearsal still require manual verification.

Device tests target the separate `verification` build (`com.example.handshakecontactcapture.verification`) using `.\gradlew.bat :app:connectedVerificationAndroidTest`. The device test runner uninstalls its target after testing; it must never target the normal installed app. Install the user-facing build with `.\gradlew.bat :app:installDebug`.

## Using photo extraction and research

### UI and branding refresh (September 13)

The app now uses a consistent forest-teal, mint, and ivory palette with a matching dark theme, stronger typography, rounded surfaces, and an 840dp maximum content width on tablets. Event dashboards show counts and follow-up status, contact lists have initials and compact previews, and contact details group selectable contact information. Capture, conversation, editor, research, and export screens use clearer section headings and action hierarchy. Export-option labels are fully tappable. Existing confirmation and privacy defaults remain in place; database schema stays at version 4.

The launcher label is **Handshake**. A generated ivory/mint handshake icon replaces the Android template through adaptive, themed-monochrome, and legacy resources. Source artwork and generation prompt are documented in [docs/design/ICON.md](docs/design/ICON.md). Images used for UI inspection contain synthetic records only.

Debug assembly, verification unit tests, and lint passed. All 29 active connected tests passed on the Android 16 tablet (two legacy speech probes skipped), including existing capture/summary/export review flows and activity recreation. UI checks additionally exercise a 360dp dark layout at 150% font scale; light/dark screenshots were visually inspected. Other OEM launcher masks, Android 7 launcher rendering, and TalkBack remain manual acceptance items.

Event details can be corrected from **Edit event** inside the event dashboard. The dialog preloads the saved name, location, and dates, requires a nonblank name, and uses saved Compose state for the draft. Saving updates the existing event ID and preserves its creation timestamp and associated connections, photos, and research. Cancel discards the draft. Debug assembly, existing verification unit tests, and debug lint passed after this change; the new editing flow has not been tested on a device. Event deletion remains planned.

1. Open **AI settings**, enter your own OpenAI API key, and save. Leave an existing key field blank to keep it. Keys are not included in APKs, chat, logs, worker inputs, or saved Compose state. The settings screen blocks screenshots. Defaults are `gpt-4.1-mini` for extraction and `gpt-4.1` for research; IDs are editable, and account access is checked by the actual request.
2. Open an event and tap **Scan card / fact sheet**. Use **Take photo** or **Choose photo**. The system camera owns camera permissions; FileProvider grants it access only to its output file. Image import does not require broad storage permission. Add front/back or up to four pages of a single document.
3. Photos are copied into private storage, rotated upright, resized to a maximum dimension of 2400 pixels, and re-encoded as JPEG without GPS/EXIF metadata. Source imports are limited to 30 MB each. Preview them before uploading; use another batch to recapture after processing starts.
4. Tap **Extract contact details**, review the upload disclosure, and confirm. WorkManager waits for a connection and processes the saved batch. Transient connection/server failures retry up to three attempts with backoff; authentication, billing/rate-limit, model, refusal, or invalid-output errors need user attention. A queued batch is recovered on app startup if scheduling was interrupted. Retries may incur API charges.
5. Open each candidate with **Review & save**. Correct every field against the photos. Up to 20 distinct people/company-only candidates can be reviewed per batch; additional pages/people require smaller batches. Printed evidence and warnings remain accessible in the batch. Saving creates an event connection; rerunning Save does not overwrite a previously edited connection.
6. Open a saved connection and tap **Research company**. Confirm its name, official website, and optional city/country. Only this identity and printed capability claims go to the research request; private meeting notes/follow-ups are excluded. Research uses web search and requires usable citation metadata before displaying a successful result. Tap inline citations or source titles to open the supporting pages.
7. Research stays separate from contact fields and private notes. The screen shows the researched identity/date and flags identity differences after contact edits. Refresh is explicit. The current implementation stores the latest result per encounter; sharing cached company research across encounters, version history, and structured capability tags remain future work.
8. **Delete photo batch** removes its photos/extraction and cancels pending extraction while retaining contacts already saved. Deleting a connection cancels its research and cascades the research row. Removing an API key stops future authenticated requests; a request already sent may still complete. Local deletion cannot retract previously uploaded content.

The live client uses `POST https://api.openai.com/v1/responses` with `store: false`, bounded image/output sizes, structured extraction, and web-search citation parsing. `store: false` does not promise zero provider retention. No custom endpoint or unauthenticated proxy is configured. Research results are AI-generated evidence summaries, and ambiguous company identities must be reviewed by the user.

Existing baseline: package `com.example.handshakecontactcapture`, Kotlin, Compose/Material 3, minimum SDK 24, target SDK 36, compile SDK 37, and Java source/target compatibility 11. Use the checked-in Gradle wrapper and inspect build configuration before selecting dependencies or the Gradle runtime JDK.

Build prerequisite: install Android SDK Platform 37 through Android Studio's SDK Manager. Core 1.19.0 and Lifecycle 2.11.0 require compile SDK 37; target SDK and minimum SDK remain 36 and 24 respectively.

## Conversation summaries: OpenAI transcription

The user switched from Android speech services to OpenAI after repeated provider failures. Open a saved connection, choose **Conversation summary**, record up to 59 seconds, then **Transcribe with OpenAI**. The confirmation explains audio upload, API charges/retries, and provider retention. Existing private PCM recordings work without recapture: the client wraps them as 16 kHz mono 16-bit WAV in memory and posts multipart data to `/v1/audio/transcriptions`. The configurable default is `gpt-4o-mini-transcribe`, using the existing encrypted personal API key. No contact/research context is included in transcription. This endpoint does not receive the Responses-only `store` field.

The draft and request are persisted before WorkManager scheduling. Transcription waits for network, retries transient failures up to three attempts, and continues after leaving the screen. Cancel invalidates the request; audio is preserved. Deleting audio/summary/connection cancels pending transcription. Completed text is persisted and appended to the saved draft for review; the latest raw transcript is retained separately. Stale or repeated results cannot overwrite edits, append twice, or recreate deleted records. Text is limited to 8,000 characters; overlong/empty responses produce a recoverable error. Recording/playback remain local, with a visible timer, 59-second limit, typed fallback, and retained samples after interruption.

After editing, **Analyze summary** separately confirms uploading reviewed text and saved company research. It uses Structured Outputs without web tools, retaining original transcript evidence while tolerating capitalization, whitespace, and smart-quote differences and saved research URLs. Suggestions include important details, needs, claimed capabilities, promised materials, introductions, follow-ups, open questions, and research agreements/conflicts. Review each before appending it to notes or the existing follow-up list. Confirmed notes/tasks survive reanalysis. One recording/latest transcription/latest analysis is retained per encounter. Starting transcription clears unaccepted analysis suggestions. Repeating transcription can append the same speech again; review the draft before retrying a successful request.

No database migration was required (schema 3). AndroidMemoTranscriber and its opt-in probes remain as legacy code/test references but are no longer called by the app's transcription flow. Earlier Android synthetic-speech success did not establish reliability for user recordings; this switch supersedes that implementation.

Debug build, unit tests, lint, and 22 routine connected tests passed; two legacy Android provider probes were skipped. Verification uses synthetic PCM and fake API responses: WAV/multipart framing, transcript validation, durable result application, duplicate completion, edit/deletion protection, and existing migration/UI checks. Paid live OpenAI transcription has not been tested. Actual recording quality and full offline/process-death rehearsal remain manual acceptance items. Official request reference: [OpenAI file transcription](https://developers.openai.com/api/docs/guides/speech-to-text).

## Core workflow

1. Create or select an event with a name, dates, location, and optional notes. Keep the active event visible throughout capture.
2. Take one or more photos: card front/back, a fact sheet, or additional supporting pages. Allow importing an existing image, retaking, and checking legibility.
3. Save the capture locally immediately. Upload for extraction when connected and remote processing is enabled; permit manual contact entry without AI.
4. Review proposed people, company, titles, phone numbers, email addresses, websites, and postal addresses alongside the source image. Correct mistakes and choose which people to save. A sheet without a named person still creates a company encounter.
5. Resolve the company using its printed domain, name, and location. Research it online and show a concise summary of products/services, capabilities, markets, and relevance to the event, with clickable sources and the research date.
6. Optionally record a personal memo of at most 59 seconds. Transcribe it, allow corrections, and propose capability notes, introductions, and follow-up tasks. Recording may happen immediately after capture while research is pending.
7. Review the combined encounter. Distinguish printed claims, web findings, personal observations, and suggested actions. Keep unresolved questions visible.
8. Browse/search the entire event, filter outstanding follow-ups, and export one record, selected records, or the event.

## Requirements and defaults

| Area | Required behavior |
| --- | --- |
| Platform | Native Android only; phones first. No iOS or web client required. |
| Events | Multiple saved events, active-event selection, event counts, and persistent history. |
| Capture | Multiple images per encounter; multiple POCs per document; company-only captures. |
| Contact fields | Name, title, organization, labeled lists of phones/emails/addresses/websites, and source references. Keep original phone formatting; normalize only with sufficient country context. |
| Company research | Short summary and capability tags, evidence URLs, researched timestamp, identity uncertainty, and manual refresh. Never use model memory as evidence of current facts. |
| Voice memo | Optional, visible timer, automatic stop at 59 seconds, playback/delete/re-record, editable transcript, and typed-note fallback. Handle interruption and empty audio. |
| Follow-ups | Action, intended person/team to connect, optional due date, priority, and open/done status. Unknown recipients or dates stay unset; suggestions require review. |
| Search | Event-scoped search by person, organization, email, capability, and notes; filters for review state and follow-ups. Cross-event history is also accessible. |
| Offline | Capture, manual editing, and browsing work offline. AI stages queue independently and expose retryable errors. |
| Export | Google Contacts and UTF-8 CSV, selectable scope, field preview, and explicit inclusion of private notes. |
| Duplicates | Suggest likely matches by normalized email/phone and company domain; names alone are insufficient for automatic merging. User chooses link, merge, or keep separate. |

Defaults: single user, local database, no app account or multi-device synchronization in the initial client, English UI, and one-way contact export. A private AI backend may require authentication even though the app has no broader account system. Event collaboration, automatic outreach, CRM integrations, and calendar synchronization are later features.

## Proposed architecture

Use Compose screens backed by ViewModels and StateFlow. Repositories coordinate Room, private media files, and network adapters. Keep AI response DTOs separate from database entities and validate before applying changes.

- `ui/`: event list, event dashboard, capture, review, encounter details, memo, follow-ups, export, settings.
- `data/local/`: Room entities, DAOs, migrations, and media storage.
- `ai/`: OpenAI client, typed response contracts, encrypted settings, and durable jobs.
- `domain/`: extraction validation, identity resolution, merge decisions, research synthesis, and export mapping.
- `capture/`: system-camera/image import and private photo storage; private PCM audio recording/playback; OpenAI transcription runs through ai/.
- `workers/`: extraction, research, transcription, and memo interpretation jobs.
- `export/`: CSV serializer and Google Contacts adapter.

These are intended boundaries; some are implemented in the files listed in Status, and the domain package remains planned. Choose compatible stable library versions during implementation. Avoid introducing a dependency-injection framework until it materially simplifies the app.

## Data model

Use stable UUIDs, created/updated timestamps, foreign keys, and explicit deletion behavior. Store media paths rather than image/audio blobs in Room.

| Entity | Purpose and important fields |
| --- | --- |
| Event | Name, dates, location, notes, archived state. |
| Company | Name, confirmed domain, websites, general contact methods. Shared across encounters. |
| Contact | Person name, role/title, company reference; no required email or phone. |
| ContactMethod | Contact or company owner, kind, label, original value, normalized value, source. Enforce exactly one owner. |
| Encounter | Event, optional company, captured timestamp, booth/context, review state, personal notes. |
| EncounterContact | Many-to-many link between encounters and people; records repeat meetings without duplicating people. |
| CaptureAsset | Encounter, image path, page order, type, timestamps. |
| FieldEvidence | Target record/field, source asset or transcript/research reference, extracted text, uncertainty, user-corrected flag. |
| CompanyResearch | Company, version, identity-match state, summary, capability tags, research time, model/prompt version. |
| ResearchSource | Research version, URL, title, retrieval time, and supported claims. Encounter records which research version it used. |
| VoiceMemo | Encounter, private audio path, duration, original transcript, edited transcript, processing state. |
| FollowUp | Encounter, optional contact, action, intended connection, priority, nullable due date, status, evidence. |
| ProcessingJob | Encounter/stage, input revision, status, attempt count, safe error detail. |
| ExportRecord | Local contact, destination/account, external identifier when available, exported revision/time, outcome. |

Company-only encounters remain valid. A contact can appear in several events through encounters. Merging records preserves event history and evidence. Deleting an event removes its encounters/media/jobs, but shared contacts and companies require reference-aware cleanup. Provide a separate explicit delete-all-data operation.

## AI processing

Use separate stages so a failed research request never loses extracted contacts and a failed transcription never blocks saving a capture.

**Image extraction:** Send legible images to a vision-capable model through the Responses API. Use Structured Outputs with a schema for company candidates, people arrays, contact methods, source image references, printed capability claims, and ambiguous fields. Validate locally, handle refusals/incomplete responses, and show uncertainty in review. Schema conformance does not guarantee factual accuracy. See [image inputs](https://developers.openai.com/api/docs/guides/images-vision) and [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).

**Company research:** Search using the company name, printed website/domain, and location. Prefer the company's own site for its stated offerings; use other sources where useful. Pause identity-dependent synthesis when several companies plausibly match. Use the Responses API web-search tool and retain citation metadata. If necessary, perform a separate schema-constrained synthesis over the collected evidence. Do not assume every model/tool/output combination is supported. Show sourced findings separately from printed marketing claims and user observations. See [web search](https://developers.openai.com/api/docs/guides/tools-web-search).

**Memo processing:** Upload saved audio to OpenAI only after confirmation, review the returned transcript, then separately request summary analysis. See the current conversation-summary workflow above.

Model IDs are implementation-time configuration decisions. Verify capability, account availability, and cost in official documentation before choosing them; do not hard-code a promise about pricing or a particular model.

### Credentials and remote processing

Recommended deployment: Android -> authenticated private backend -> OpenAI. Configure the user's OpenAI key as a server-side secret, restrict the backend to required operations, authenticate the device, limit request sizes/rates, and avoid retaining uploaded content unnecessarily. OpenAI recommends keeping keys out of application code and using secure secret handling; see [production best practices](https://developers.openai.com/api/docs/guides/production-best-practices).

For this personal prototype, the user chose runtime key entry and Android Keystore-backed AES-GCM storage. This is implemented in AiSettings. It is not equivalent to keeping a secret off the device: a compromised device can expose a usable credential. Never ship a shared key in an APK or paste keys into ChatGPT/repository files. The backend described above is a future production option.

Explain what is uploaded before enabling remote processing. Public company research queries should contain company identity only, not private memo text or unnecessary personal contact details. Image-batch and audio deletion are implemented. Deleting a summary or connection removes its associated audio and cancels summary analysis. Backup rules exclude private database/files/preferences. A local delete cannot promise deletion from external exports or service retention systems.

### Reliability and cost control

Persist per-stage states: queued, running, needs-review, succeeded, failed, cancelled. Use WorkManager with network constraints, bounded retries/backoff for transient failures, and actionable authentication/quota errors. Avoid duplicate local records when work repeats. Remote retries may still incur charges; do not claim exactly-once API execution.

Version inputs and apply results only if the record still exists and the relevant revision matches. Retain user edits, cancel jobs on deletion, and use durable app-private files rather than short-lived cache paths for queued uploads. Cache research by confirmed company identity and timestamp; allow deliberate refresh. Bound image sizes, search work, and output length. Show stage progress and usage when available.

## Google Contacts export

The user must be able to export actual Google Contacts, not accidentally save everything to a device-only address book. Export is one-way; it does not replace the local event database.

Initial design: preview selected contacts and let the user select a writable Google account through Android's Contacts integration. Implement and verify account targeting, required permissions, batch writes, and destination identifiers on a real device. Cloud synchronization is performed by the device's Google account sync system; a successful local provider write is not proof that cloud sync completed. If no writable Google account exists or permission is denied, retain records and offer CSV.

A system contact-insert editor can support a simple single-contact flow, but do not call that unattended event-wide export. If the provider route cannot meet reliable bulk Google-account export requirements, use the Google People API with OAuth in a later implementation decision. Verify current official Android/Google documentation before implementing either path.

Map names, organizations, roles, labeled phones/emails, addresses, and websites. Make summary, event context, and personal notes selectable; private notes/transcripts are excluded by default. Track per-contact success/failure and destination IDs where available. Re-export should update previously exported records only after review; flag possible pre-existing matches rather than silently overwriting them. Do not automatically delete Google contacts when local encounters are deleted.

## CSV export contract

Export verification (September 13): debug assembly, verification unit tests, and lint passed. All 27 routine connected tests passed on the unlocked Android 16 tablet (two legacy speech probes skipped), including export preview/privacy across activity recreation and existing UI/migration checks. Initial UI runs were blocked by the device lock screen. Real Google-account writes/cloud sync, permission-denial/account-removal interaction, and spreadsheet application behavior remain manual acceptance items.

Implemented export workflow: choose **Export event** or **Export connection**, select records, and preview CSV fields or choose a Google account. Research and event context are selectable; private notes/follow-ups and transcripts default off. CSV uses the system file picker without Contacts permission. Google export requests Contacts permissions, verifies the selected account and upload-capable sync adapter, displays the destination and replacement warnings, and requires final confirmation. No messages are sent.

The export/ package separates formatting, provider operations, and ViewModel state; ui/ExportScreen.kt provides selection and review. A frozen preview survives activity recreation. Exports run off the UI thread; they are not automatically restarted after process death. Google writes use one atomic batch per contact, persistent per-account history, and an app marker in a custom Contacts data row. Re-export locates that marker and checks the provider version before replacing the previewed fields/notes. Photos and other data types remain. Email/phone matches are flagged and skipped unless explicitly approved as separate contacts. Ambiguous/missing ownership markers block updates. Local deletion never deletes exported contacts; history intentionally survives local contact deletion.

CSV preserves one row per encounter, repeated methods as JSON arrays, multiline addresses, Unicode, citations, and optional reviewed notes/transcripts. Event dates are currently freeform: event_dates_text preserves them while start/end remain blank. company_id is blank and capabilities_json is empty until normalized companies/capabilities exist; printed claims are separate. Spreadsheet-sensitive cells (including leading-zero numbers) receive a leading apostrophe before CSV quoting. Consumers wanting the original text must account for that prefix. Spreadsheet application behavior and cloud synchronization require separate manual acceptance. Photos/audio are not bundled; this is not a restore format or Google-import template.

Synthetic tests cover CSV round trips/privacy/formula prefixes, schema-3 migration preserving memos, explicit Google account values in generated operations, and real-device disposable local Contacts rows with repeated fields, marker retention, updates, and stale-version rejection. These tests do not write to a real Google account. Official implementation references: [Android Contacts provider](https://developer.android.com/identity/providers/contacts-provider), [AccountManager](https://developer.android.com/reference/android/accounts/AccountManager), and [RawContacts](https://developer.android.com/reference/android/provider/ContactsContract.RawContacts).

Provide a full event archive CSV and optionally a separate Google-import CSV profile once its current header format is verified. Generic CSV must not be advertised as Google-import compatible without testing.

Full archive v1 uses one row per encounter/contact pair, with one row and an empty contact ID for company-only encounters. This preserves repeat meetings. Repeated contact methods, capabilities, source URLs, and follow-ups are JSON arrays inside quoted CSV cells so no values are silently dropped.

Implemented fixed headers:

```text
schema_version,event_id,event_name,event_start_date,event_end_date,encounter_id,captured_at,company_id,company_name,company_website,contact_id,full_name,job_title,phones_json,emails_json,addresses_json,websites_json,company_summary,capabilities_json,research_sources_json,researched_at,personal_notes,memo_transcript,followups_json,review_status,event_location,event_dates_text,printed_claims,research_identity
```

Use UTF-8, a header row, ISO 8601 dates/timestamps, and correct CSV quoting for commas, quotes, and newlines. Preserve Unicode and leading zeros. Protect spreadsheet-facing text cells against formula execution, including dangerous leading characters after whitespace; document any prefix applied so consumers can interpret the export. Test both CSV syntax and spreadsheet behavior.

Let users choose a destination through Android's Storage Access Framework and optionally share through a content URI. Preview event/contact scope and whether notes/transcripts are included; excluded private fields remain blank. CSV contains structured data, not bundled photos/audio, and is not a full-fidelity backup. Do not promise restore until an import/backup feature is implemented.

## Implementation milestones and acceptance

Milestone 1 remains **partially implemented** with remaining relationship/UI work listed above. Milestones 2–4 have an **initial working implementation**, verified with synthetic device tests; live API/camera/offline acceptance and the listed research/data-model enhancements remain. Milestone 5 has an **initial implementation** using OpenAI transcription and reviewed suggestions; real microphone/playback/speech-provider acceptance remains. Milestone 6 has an **initial implementation**, with real-account/cloud-sync and spreadsheet-app acceptance remaining. Milestone 7 is **not started**.

1. **Local event database and UI:** Create events, manually add/edit contacts and company-only encounters, search, and relaunch without data loss. Verify at least 100 synthetic encounters, multiple people per company, and the same person at two events.
2. **Capture and review:** Camera/import, multiple images, persistent files, extraction interface with fake responses, and editable review. Verify rotation/process recreation, denied camera permission, unreadable images, and a sheet with multiple POCs.
3. **Live extraction:** Resolve credential deployment, implement validated vision output, queueing, errors, and retry. Verify offline capture survives restart, unknown fields stay empty, and delayed results preserve corrections.
4. **Sourced company research:** Confirm identity, generate summaries with evidence links, cache/refresh, and handle conflicting sources or no match. Verify similarly named companies are not silently conflated.
5. **Voice memo and follow-ups:** Record up to 59 seconds, transcribe/edit, generate reviewable notes/tasks, and mark follow-ups done. Verify interruption, permission denial, silence, retry, and transcript edits.
6. **Exports:** Event/selection CSV plus Google-account export with preview and per-record outcomes. Verify Unicode/quoting/formula safety, all repeated fields, no-account/permission errors, repeated export, and real-device Google sync separately.
7. **Event readiness:** Verify offline and recovery flows, deletion/cancelled jobs, backup policy, accessibility, large-text layouts, and database migrations. Complete an end-to-end event rehearsal with synthetic data before using real trade-show records.

For implementation changes, run relevant unit tests, build, and lint via the Gradle wrapper. Device-dependent acceptance requires an emulator or physical device; report when unavailable. DatabaseTest verifies 100 encounters, repeat-event relationships, company-only records, reopen persistence, follow-up state, and reference-preserving deletion. HomeScreenTest exercises event creation, saving a company, and activity recreation using synthetic records that are removed afterward.

## Continuing with ChatGPT

Source repository: [postbk/HandshakeContactCapture](https://github.com/postbk/HandshakeContactCapture), private, with `main` as the backup branch and `origin` configured locally. Git and GitHub CLI are available in the ignored `.tools/git/cmd` and `.tools/gh/bin` folders on this workstation because the Windows package-manager source was unavailable. Use their full paths or add these folders to the session PATH. Credentials, IDE/build caches, local SDK paths, signing keys, and device data are excluded. This source backup does not include the user's tablet notebook or media; see README.md for backup scope. Future changes require a commit and push to update GitHub.

Share this file and AGENTS.md together with the relevant source files; do not share real API keys or private attendee records. Suggested starting prompt:

> Read AGENTS.md and PROJECT.md. Continue the Kotlin/Compose app from its current photo extraction and company research implementation. Preserve existing records and the user's in-app encrypted-key choice. Use synthetic data for tests, report live-test limitations, and update the status with what works and what remains.

The user has selected the personal-device key prototype. The device exposes an upload-capable Google Contacts sync adapter; real-account/cloud-sync acceptance remains before event use.

Summary validation update: harmless case/whitespace/smart-quote differences now match back to the original transcript text. Blank optional recipient/timing values become null. Valid suggestions survive alongside rejected ones, with visible omission warnings; invented evidence, unsupported recipients/dates, and unknown research URLs remain rejected. If every suggestion fails, analysis reports that limitation rather than presenting unsupported content. The original rejected live response was not stored, so its specific failing field could not be diagnosed retrospectively. Regression tests cover mixed valid/invalid results and original-evidence preservation.
