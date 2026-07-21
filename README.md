<img src=".github/images/icon.svg" width="72" height="72" align="left" alt="PicPocket icon">

# PicPocket

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
- **Document tagging** — Color-coded tags with Gmail-style autocomplete; manage tags individually or batch on multiple documents via multi-select
- **Dark mode** — System/Light/Dark with 4 color palettes (Royal, Blue, Teal, Green)
- **Page size selection** — A0–A6, Letter, Legal, Tabloid
- **SAF save location** — User picks where to save via Storage Access Framework; no storage permissions needed

## Requirements

- Android 8.0 (API 26) or newer
- Google Play Services (for ML Kit)

## Quick Start

```bash
# Clone
git clone https://github.com/zunftw/picpocket.git
cd picpocket

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
./gradlew testDebug              # Run unit tests (108+ tests)
./gradlew assembleRelease        # Build release APK (requires signing config)
```

## Project Structure

```
app/
├── src/
│   ├── main/
│   │   ├── java/com/picpocket/app/
│   │   │   ├── data/
│   │   │   │   ├── local/       — Room database, DAOs, entities (documents, pages, tags)
│   │   │   │   └── repository/  — DocumentRepository implementation
│   │   │   ├── domain/
│   │   │   │   └── pdf/         — PdfGenerator, PageSize, OCR logic
│   │   │   ├── ui/
│   │   │   │   ├── screens/
│   │   │   │   │   ├── home/    — Document list with search, multi-select tagging
│   │   │   │   │   ├── scanner/ — Camera + capture workflow, tag during creation
│   │   │   │   │   ├── detail/  — Page grid, reorder, delete, tag management
│   │   │   │   │   ├── viewer/  — Full-screen zoomable viewer
│   │   │   │   │   ├── tags/    — Tag management (create, rename, delete)
│   │   │   │   │   └── settings/— Theme, PDF options, storage, tags entry
│   │   │   │   └── theme/       — Material3 theming, palettes
│   │   │   ├── components/      — Reusable composables (tag selector, etc.)
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
| Testing | JUnit 4, Robolectric, Turbine, MockK |

## Support

If you find this app useful, consider supporting its development:

[![Ko-fi](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-ffdd00?logo=buymeacoffee&logoColor=black)](https://ko-fi.com/pipolarbear)

<details>
<summary><b>Cryptocurrency</b></summary>

| | Address | QR |
|---|---|---|
| <img src=".github/images/btc.svg" width="20"> **BTC** | `bc1qgyffnlhp2uz2uhpmhfrspc5qxpj3y9m4lwgga5` | <img src=".github/images/btc-qr.png" width="64"> |
| <img src=".github/images/eth.svg" width="20"> **ETH** | `0x581b4810873698505FDF3aAf0a39430bb0D7d655` | <img src=".github/images/eth-qr.png" width="64"> |
| <img src=".github/images/sol.svg" width="20"> **SOL** | `6awadeXmfc7JUMQL5SEgZXDE4yaFDgWkPNRySLDDmh7E` | <img src=".github/images/sol-qr.png" width="64"> |

</details>

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
