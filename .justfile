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
    adb install -r {{APK_PATH}}
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
