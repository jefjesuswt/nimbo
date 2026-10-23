# Changelog

All notable changes to Nimbo are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Removed
- Translation in the text reader is no longer part of the public build (it relied on an unofficial Google endpoint). It now lives only in the `personal` build, with ML Kit on the phone and, optionally, the Google web endpoint. The public APK stays at about 40 MB.

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

[Unreleased]: https://github.com/jefjesuswt/nimbo/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/jefjesuswt/nimbo/releases/tag/v1.0.0
