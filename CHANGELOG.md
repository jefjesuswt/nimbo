# Changelog

All notable changes to Nimbo are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.2.0] - 2026-10-27

### Added
- Files bigger than Telegram's limit (2 GB, 4 GB with Premium) are uploaded in parts (`name.ext.001`, `.002`…) and shown as one file. Videos stream across parts with normal seeking; downloading joins the parts. Outside Nimbo the parts join with 7-Zip.

## [1.1.0] - 2026-10-26

### Added
- README and Telegram API keys guide in English and Spanish, and a Limits section (2 GB per file, caption length, inactive accounts…).

### Changed
- Translation in the text reader now runs on the phone with ML Kit: the text never leaves the device. Language models (about 30 MB each) download on first use, on Wi-Fi by default (can be allowed on mobile data in More).
- The unofficial Google Translate web endpoint is no longer in the public build.
- Internal rewrite of the app architecture (dependency injection, one ViewModel per screen, typed navigation). No change to how files are stored in Telegram.
- Downloads now time out only when they stop making progress, so large files on slow connections no longer fail after 3 minutes.

### Fixed
- Renaming a track or finding its cover art now updates the list right away.
- Signing out now clears the previous account's file list, offline index and playback positions.
- Uploads and downloads no longer get marked as failed when Telegram reports progress in bursts.
- Thumbnail and cover caches are capped, avoiding out-of-memory crashes in large libraries.
- Renaming or moving a folder now moves the files directly inside it to the new path (they used to end up one level too deep).
- Choosing "wrong number" on the code screen and sending the number again now shows the code screen.
- Messages from the photo viewer, text reader and player (saved, failed…) are now visible.
- With the Dark theme on a phone set to light mode, the status bar icons were dark on dark and hard to see.

## [1.0.0] - 2026-10-23

First public release.

### Cloud
- Your files live in your own Telegram **Saved Messages**; Nimbo keeps a local index to load fast and work offline.
- Virtual folders (a tag in each caption), so they also make sense in the official Telegram app.
- Upload files and whole folders in the background, with progress, queue details, cancel and conflict handling (replace / keep both / skip).
- Move, copy, rename and delete files and folders; delete with **Undo**.
- Download to `Download/Nimbo`.
- Search across the whole tree (accent and case insensitive, all words must match).
- Sort by name, date, size or duration; list or grid view per tab.
- Pull to refresh.

### Tabs
- **Files**, **Audio**, **Photos** and **More**, with a Mihon-style top bar.
- Audio and Photos are grouped into albums (folders) with cover, track/photo counts, duration, size and date.

### Players
- Music player with queue, repeat, shuffle, speed, sleep timer and favorites; plays in the background with a media notification.
- Video player: full screen, double tap to seek ±10 s, swipe for brightness and volume, speed, fit/fill, rotate, picture-in-picture and side-loaded `.srt` / `.vtt` subtitles.
- Videos stream while they download; seeking jumps the download to that point.
- Resume where you left off, with a progress bar on started files.
- Album art only from the file's embedded picture or a `cover.jpg` / `folder.png` (and similar) in the same folder.

### Viewers
- Photo viewer with zoom, filmstrip and gallery.
- Text reader for many formats with encoding detection and translation.

### App
- Languages: English, Spanish, Portuguese (Brazil), French and Japanese; follows the system language or can be set per app.
- Material You dynamic color, light/dark theme and themed (monochrome) icon.
- Works in landscape without losing your place.

### Security
- Your `api_id` / `api_hash` are typed in the app and stored encrypted on the phone only; Nimbo ships no keys.
- The Telegram session database is encrypted and excluded from backups.

[Unreleased]: https://github.com/jefjesuswt/nimbo/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/jefjesuswt/nimbo/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/jefjesuswt/nimbo/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/jefjesuswt/nimbo/releases/tag/v1.0.0
