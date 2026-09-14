# Handshake

A native Android event notebook for trade-show connections. Capture business cards, review contact details, research companies, record conversation summaries, track follow-ups, and export to CSV or Google Contacts.

Built with Kotlin, Jetpack Compose, Room, and WorkManager. Read [PROJECT.md](PROJECT.md) for current behavior, architecture, verification, and limitations; [AGENTS.md](AGENTS.md) describes contributor conventions.

## Build

Open in Android Studio, install Android SDK Platform 37, and use the checked-in Gradle wrapper. Configure the SDK location locally; `local.properties` is intentionally excluded.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:assembleDebug :app:testVerificationUnitTest :app:lintDebug
```

Device tests use `:app:connectedVerificationAndroidTest`, which targets a separate `.verification` app and protects the normal installed notebook.

Enter a personal OpenAI API key in the app's settings. Keys are encrypted with Android Keystore and must never be committed to this repository.

## Backup scope

This repository backs up source code, Gradle configuration, Room schemas, tests, documentation, and design assets. It does **not** back up the tablet's contacts, event database, recordings, photos, API key, or signing keys. CSV export can preserve selected structured event records, but is not a full restore format.
