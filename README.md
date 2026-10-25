<p align="center"><img src="docs/logo.svg" width="96" alt="Nimbo"></p>

<p align="center"><b>English</b> · <a href="README.es.md">Español</a></p>

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

## Download

Grab the latest APK from [Releases](https://github.com/jefjesuswt/nimbo/releases). See [CHANGELOG.md](CHANGELOG.md) for what changed.

## Setup

1. Create an `api_id` / `api_hash` at <https://my.telegram.org> → API development tools.
2. Open Nimbo, paste them (stored encrypted on the phone only), sign in with your number.

You get two different codes from Telegram: one (letters and numbers) to log in to
my.telegram.org, and later a 5-digit one to sign in to Nimbo. Step by step, with
the form fields and the usual "ERROR" fix: [docs/telegram-api-keys.md](docs/telegram-api-keys.md).

## Limits

Nimbo stores everything as messages in your Saved Messages, so Telegram's own
limits apply:

- **2 GB per file** (4 GB with Telegram Premium). Bigger files fail to upload;
  Nimbo doesn't split them.
- **Captions are limited to 1024 characters** (2048 with Premium). The folder path
  and the music tags live in the caption, so extremely long or deeply nested
  folder names can fail to save.
- **Rate limits.** Telegram slows down accounts that send many requests in a row.
  Moving or renaming a folder with many files edits each caption one by one with a
  short pause, so large batches take a while.
- **Download speed** may be lower without Premium; that depends on Telegram, not Nimbo.
- **Inactive accounts are deleted.** Telegram deletes an account (and its Saved
  Messages) after a period without use (6 months by default; change it in
  Telegram → Settings → Privacy and Security → Delete my account → If away for).
  Nimbo is not a backup: keep a copy of anything you can't lose.
- **Not end-to-end encrypted.** Saved Messages is a cloud chat: it's encrypted in
  transit and stored on Telegram's servers, like any other cloud chat.
- Total storage in Saved Messages has no published limit.

## Build

```sh
./gradlew testPublicDebugUnitTest assemblePublicDebug
adb install -r app/build/outputs/apk/public/debug/app-public-debug.apk
```

Two flavors:

- `public` (default, the one in Releases): translation runs **on the phone** with ML Kit.
- `personal`: also offers the unofficial Google Translate web endpoint (sends the text to Google). Not published.

```sh
./gradlew assemblePublicRelease     # GitHub Releases
./gradlew assemblePersonalDebug     # your own phone
```

Needs JDK 17 and the Android SDK (`ANDROID_HOME`, defaults to `~/Android/Sdk`).

## License

[MIT](LICENSE)
