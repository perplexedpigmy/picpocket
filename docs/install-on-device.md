# Install DocScanner on a Physical Android Device

## Prerequisites

- A physical Android device running **Android 8.0 (API 26)** or newer
- **USB cable** to connect the device to your computer
- **USB Debugging** enabled on the device (see Step 1)
- Android SDK installed (for `adb` command)

---

## Step 1 — Enable Developer Options & USB Debugging

On your Android device:

1. Open **Settings** → **About phone**
2. Tap **Build number** 7 times until "You are now a developer!" appears
3. Go back → **System** → **Developer options**
4. Toggle **USB debugging** ON
5. Connect the device to your computer via USB
6. When prompted "Allow USB debugging?" on the device, check **Always allow from this computer** and tap **OK**

Verify the device is recognized:

```bash
adb devices
```

You should see output like:
```
List of devices attached
RF8N21CXXXX	device
```

If the device shows as `unauthorized`, check the device screen for the authorization prompt.

---

## Step 2 — Build the debug APK

```bash
cd /home/zun/dev/oc/pdfscanner
./gradlew assembleDebug
```

The APK is generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## Step 3 — Install via ADB

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The `-r` flag reinstalls if an existing version is found (useful for updates).

To verify the install:

```bash
adb shell pm list packages | grep docscanner
# Output: package:com.docscanner
```

---

## Step 4 — Build a signed release APK (for sharing)

Debug APKs are signed with a debug keystore. For a distributable APK, generate a signed release build:

### 4a. Create a keystore (one time)

```bash
keytool -genkey -v -keystore ~/docscanner-keystore.jks \
  -alias docscanner -keyalg RSA -keysize 2048 -validity 10000
```

### 4b. Configure signing in `app/build.gradle.kts`

Add inside the `android { ... }` block:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("KEYSTORE_PATH") ?: "/home/zun/docscanner-keystore.jks")
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS") ?: "docscanner"
        keyPassword = System.getenv("KEY_PASSWORD")
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        // ...
    }
}
```

### 4c. Build the release APK

```bash
export KEYSTORE_PASSWORD=your-password
export KEY_PASSWORD=your-password
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

---

## Step 5 — Share the APK directly

Without a computer, you can share the APK file directly:

1. Copy `app-debug.apk` to Google Drive, email, or a file-sharing service
2. On the Android device, download the file
3. Tap the downloaded APK
4. If prompted, allow installation from "Unknown sources" or your file manager app
5. Tap **Install**

---

## Notes

### Google Play Services requirement

ML Kit Document Scanner requires **Google Play Services** installed on the device. This is pre-installed on:
- All Google-certified Android devices (Samsung, Pixel, OnePlus, Xiaomi, etc.)
- Devices with Google Play Store access

It will **not** work on:
- Devices without Google Play Services (e.g. Amazon Fire tablets without Google Play)
- Custom ROMs without Google apps (e.g. LineageOS without GApps)

### Permissions

The app requests:
- **Camera** — for the document scanner (requested by ML Kit internally on first scan)
- No storage permissions are needed — documents are saved via SAF (Storage Access Framework), letting the user pick where to save

### Updating the app

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The `-r` flag preserves the app's data.

### Uninstalling

```bash
adb uninstall com.docscanner
```

Or on the device: Settings → Apps → DocScanner → Uninstall.
