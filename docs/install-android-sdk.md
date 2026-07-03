# Install Android SDK (Command-Line)

## Dependencies

- **Java 17+** — verify with `java -version`
- **wget** — `sudo apt install wget` (Pop!/Ubuntu/Debian)
- **unzip** — `sudo apt install unzip`

## Installation

```bash
# Create SDK root (or wherever you prefer)
mkdir -p ~/.local/android-sdk/cmdline-tools
cd ~/.local/android-sdk/cmdline-tools

# Download command-line tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
mv cmdline-tools 11076708
ln -s 11076708 latest
rm commandlinetools-linux-*.zip
cd

# Install SDK platforms and build tools
export ANDROID_HOME=$HOME/.local/android-sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  --sdk_root=$ANDROID_HOME
```

## Directory structure

```
~/.local/android-sdk/
├── cmdline-tools/
│   ├── 11076708/          ← versioned release
│   │   ├── bin/
│   │   ├── lib/
│   │   └── ...
│   └── latest -> 11076708 ← symlink, always points to current
├── build-tools/
│   └── 34.0.0/
├── platforms/
│   └── android-34/
├── platform-tools/        ← adb, fastboot
├── licenses/
└── .knownPackages
```

## Environment variable

Add to `~/.zshenv` (sourced by both zsh and child processes):

```bash
export ANDROID_HOME=$HOME/.local/android-sdk
```

To pick it up in the current shell:

```bash
source ~/.zshenv
```

## Project-level config

In the Android project root (`/home/zun/dev/oc/pdfscanner/`), create `local.properties`:

```bash
echo "sdk.dir=$HOME/.local/android-sdk" > /home/zun/dev/oc/pdfscanner/local.properties
```

## Build

```bash
cd /home/zun/dev/oc/pdfscanner
./gradlew assembleDebug
```

## Add more SDK components

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  --sdk_root=$ANDROID_HOME
```

List available packages:

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --list --sdk_root=$ANDROID_HOME
```
