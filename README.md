<div align="center">

# Needle

**A calm, neumorphic voice journal for Android** — record, browse, and play back moments with a UI that feels like a turntable, not a spreadsheet.

<img src="https://raw.githubusercontent.com/TUHS-lab/Needle-Recorder/main/preview/needle.png.png" alt="Needle — home and playback" width="720" />

<br />

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/license-MIT-9C7C5C?style=flat-square)](LICENSE)

</div>

---

## Why Needle

- **One surface, one mood** — warm cream palette, soft shadows, no visual noise.
- **Record with confidence** — foreground service and notification keep long takes honest on modern Android.
- **Playback that fits** — scrub, speed, vinyl-inspired motion; pause keeps the tonearm and platter where you left them.
- **Hybrid UI** — Kotlin shell (edge-to-edge, permissions, audio pipeline) + a focused WebView experience in [`app/src/main/assets/design/app.html`](app/src/main/assets/design/app.html).

## Download

[Download APK](https://github.com/TUHS-lab/Needle-Recorder/releases)

## Requirements

- **Android 10+** (API 29)
- **Microphone** (optional hardware — app handles absence gracefully where possible)

## Permissions (high level)

| Permission | Why |
|------------|-----|
| `RECORD_AUDIO` | Capture voice notes |
| `FOREGROUND_SERVICE` / `…_MICROPHONE` | Reliable recording while backgrounded (API-dependent) |
| `POST_NOTIFICATIONS` | Recording status on Android 13+ |
| `INTERNET` | Web fonts for the embedded UI |

## Project layout

```
app/
├── src/main/java/com/omni/jrnl/   # Activity, recorder, WebView bridge, services
├── src/main/assets/design/app.html # SPA: home, library, playback, settings
└── src/main/res/                  # Themes, strings, launcher icons
```

## License

[MIT](LICENSE) — Copyright (c) 2026 TUHS

---

<div align="center">

**Needle** · *application*

Made with care for small screens and long listens.

</div>
