<p align="center"><img src="docs/logo.svg" width="96" alt="Nimbo"></p>

# Nimbo

Your files, music and photos in your own Telegram Saved Messages, with real
folders, a music player and a photo viewer. Android, Kotlin + Material 3.

Not affiliated with Telegram.

## How it works

- Everything lives in **your** Saved Messages. Nimbo keeps a local index only to
  load fast and work offline.
- Folders are a tag in each file's caption (`#nimbo/music/albums`), so they
  also make sense in the official Telegram app.
- Album art comes from the audio's embedded picture or from a `cover.jpg` /
  `folder.png` (and similar) in the same folder.

## Setup

1. Create an `api_id` / `api_hash` at <https://my.telegram.org> → API development tools.
2. Open Nimbo, paste them (stored encrypted on the phone only), sign in with your number.

## Build

```sh
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17 and the Android SDK (`ANDROID_HOME`, defaults to `~/Android/Sdk`).
