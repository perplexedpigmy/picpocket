# Run DocScanner in an Android Emulator

## Prerequisites

- **Android SDK** already installed at `$HOME/.local/android-sdk`
- **ANDROID_HOME** set in `~/.zshenv`: `export ANDROID_HOME=$HOME/.local/android-sdk`
- **Java 17+** — verify with `java -version`

## Step 1 — Install a system image

List available Android 34 images:

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  --list --sdk_root=$ANDROID_HOME \
  | grep "system-images;android-34"
```

Pick one with **Google APIs** or **Google Play** (required for ML Kit Document Scanner):

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "system-images;android-34;google_apis;x86_64" \
  --sdk_root=$ANDROID_HOME
```

## Step 2 — Create an AVD

### Via command line

```bash
echo no | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager \
  create avd -n Pixel_6_API_34 \
  -k "system-images;android-34;google_apis;x86_64" \
  -d pixel_6
```

### Via Android Studio (simpler)

1. Open Android Studio → Tools → Device Manager
2. Click **Create device**
3. Select a phone (e.g. Pixel 6) → Next
4. Select the **API 34** system image with **Google APIs** → Next
5. Name it (e.g. `Pixel_6_API_34`) → Finish

## Step 3 — Run the emulator

### From command line

```bash
$ANDROID_HOME/emulator/emulator -avd Pixel_6_API_34 -no-snapshot &
```

The `&` puts it in the background. Wait for the boot animation to finish (usually 30–60s the first time).

Check it's ready:

```bash
adb devices
# Should show: emulator-5554   device
```

### From Android Studio

Click the green ▶️ triangle next to your AVD in the Device Manager panel.

## Step 4 — Build and install

```bash
cd /home/zun/dev/oc/pdfscanner

# Build the debug APK
./gradlew assembleDebug

# Install on the running emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first build, `./gradlew assembleDebug` will automatically install if an emulator is running. Otherwise `adb install` does it.

The app icon will appear in the emulator's app drawer under "DocScanner".

## Step 5 — Run instrumentation tests

Requires a running emulator or connected device:

```bash
./gradlew connectedDebugAndroidTest
```

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `emulator: ERROR: x86_64 emulation currently requires hardware acceleration!` | Install KVM: `sudo apt install qemu-kvm libvirt-daemon-system` then add user to kvm group: `sudo adduser $USER kvm` and log out/in |
| Emulator is slow | Use the `-gpu host` flag: `emulator -avd Pixel_6_API_34 -gpu host` |
| "Google Play Services is updating" | Wait a few minutes, or go to Settings → Apps → Google Play Services → Clear data |
| ML Kit Document Scanner doesn't work | Ensure the AVD uses a **Google APIs** or **Google Play** system image (not the plain `x86_64` one) |
| `adb: command not found` | Ensure `$ANDROID_HOME/platform-tools` is in your PATH |
