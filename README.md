<div align="center">
  <img src=".github/images/icon.svg" width="96" height="96" alt="DocScanner icon">
</div>

# DocScanner

[![Android](https://img.shields.io/badge/Android-26%2B-3DDC84?logo=android)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.02.00-4285F4?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![ML Kit](https://img.shields.io/badge/ML%20Kit-Document%20Scanner%20%2B%20OCR-4285F4?logo=google)](https://developers.google.com/ml-kit)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A document scanner for Android that auto-detects document bounds, captures multi-page documents, performs on-device OCR, and saves as searchable PDFs.

## Features

- **Auto-capture** — ML Kit Document Scanner API detects document boundaries and captures with perspective correction
- **Multi-page documents** — Scan multiple pages, preview, reorder, and save as a single PDF
- **On-device OCR** — ML Kit Text Recognition extracts text offline; no data leaves the device
- **Searchable PDFs** — Invisible text layer embedded in generated PDFs so you can search document content
- **Image filters** — Grayscale, brightness, contrast, sharpen, and binarize per page
- **Drag-and-drop reorder** — Rearrange pages in edit mode
- **Full-screen viewer** — Swipe between pages, pinch-to-zoom, double-tap to reset
- **Search documents** — Regex search across document names and OCR content with debounce
- **Dark mode** — System/Light/Dark with 4 color palettes (Royal, Blue, Teal, Green)
- **Page size selection** — A0–A6, Letter, Legal, Tabloid
- **SAF save location** — User picks where to save via Storage Access Framework; no storage permissions needed

## Requirements

- Android 8.0 (API 26) or newer
- Google Play Services (for ML Kit)

## Quick Start

```bash
# Clone
git clone https://github.com/zunftw/docscanner.git
cd docscanner

# Create local.properties
echo "sdk.dir=\$HOME/Android/Sdk" > local.properties

# Build and install on connected device
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Building from source

The project uses the standard Android Gradle plugin. No special setup is required beyond an installed Android SDK.

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew testDebug              # Run unit tests (90+ tests)
./gradlew assembleRelease        # Build release APK (requires signing config)
```

## Project Structure

```
app/
├── src/
│   ├── main/
│   │   ├── java/com/docscanner/
│   │   │   ├── data/
│   │   │   │   ├── local/       — Room database, DAOs, entities
│   │   │   │   └── repository/  — DocumentRepository implementation
│   │   │   ├── domain/
│   │   │   │   └── pdf/         — PdfGenerator, PageSize, OCR logic
│   │   │   ├── ui/
│   │   │   │   ├── screens/
│   │   │   │   │   ├── home/    — Document list with search
│   │   │   │   │   ├── scanner/ — Camera + capture workflow
│   │   │   │   │   ├── detail/  — Page grid, reorder, delete
│   │   │   │   │   ├── viewer/  — Full-screen zoomable viewer
│   │   │   │   │   └── settings/— Theme, PDF options, storage
│   │   │   │   └── theme/       — Material3 theming, palettes
│   │   │   ├── navigation/      — NavGraph
│   │   │   └── di/              — Hilt dependency injection
│   │   └── res/
│   │       ├── drawable/        — App icon (adaptive vector)
│   │       └── ...
│   └── test/                    — JVM unit tests (Robolectric)
build.gradle.kts                 — App-level Gradle config
settings.gradle.kts              — Project-level Gradle config
```

## Tech Stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Material3 |
| Architecture | MVVM with Hilt DI |
| Database | Room (SQLite) |
| Document scanning | ML Kit Document Scanner API |
| OCR | ML Kit Text Recognition v2 |
| PDF | Android `PdfDocument` API |
| Navigation | Jetpack Navigation Compose |
| Testing | JUnit 5, Robolectric, Turbine, MockK |

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
