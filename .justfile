# Justfile for DocScanner Android Project
# Provides convenient tasks for common operations

APP_NAME := "com.docscanner"
APK_PATH := "app/build/outputs/apk/debug/app-debug.apk"

default: help

# Print available commands
help:
    @echo "Available commands:"
    @echo "  build           Build debug APK"
    @echo "  test            Run unit tests"
    @echo "  install         Install APK on connected device"
    @echo "  uninstall       Remove app from connected device"
    @echo "  lint            Run linting"
    @echo "  clean           Clean build artifacts"

# Build the project (debug)
build:
    @echo "Building..."
    ./gradlew assembleDebug
    @echo "Build completed!"

# Run unit tests
test:
    @echo "Running unit tests..."
    ./gradlew testDebugUnitTest
    @echo "Tests completed!"

# Install APK on connected device
install: build
    adb -d install -r {{APK_PATH}}
    @echo "Installed successfully!"

# Uninstall from connected device
uninstall:
    adb uninstall {{APP_NAME}}
    @echo "Uninstalled!"

# Run lint
lint:
    ./gradlew lint

# Clean build artifacts
clean:
    rm -rf app/build .gradle
    @echo "Cleaned!"

# Setup emulator (one-time, requires sdkmanager + avdmanager)
setup-emulator:
    @echo "Setting up emulator..."
    SDK_ROOT=$${ANDROID_SDK_ROOT:-~/.local/android-sdk}
    $$SDK_ROOT/11076708/bin/sdkmanager --sdk_root=$$SDK_ROOT "system-images;android-34;google_apis;x86_64"
    echo "no" | $$SDK_ROOT/11076708/bin/avdmanager create avd -n testPixel7 -k "system-images;android-34;google_apis;x86_64" -d pixel_7
    @echo "Emulator ready! Use 'just smoke-test' to run tests."

# Run smoke tests (requires setup-emulator first)
smoke-test:
    @echo "Running smoke tests..."
    bash scripts/smoke-test.sh
    @echo "Smoke tests complete!"

# Remove emulator AVD + system image
cleanup-emulator:
    @echo "Cleaning up..."
    SDK_ROOT=$${ANDROID_SDK_ROOT:-~/.local/android-sdk}
    $$SDK_ROOT/11076708/bin/avdmanager delete avd -n testPixel7 || true
    yes | $$SDK_ROOT/11076708/bin/sdkmanager --sdk_root=$$SDK_ROOT --uninstall "system-images;android-34;google_apis;x86_64" || true
    @echo "Cleaned!"
