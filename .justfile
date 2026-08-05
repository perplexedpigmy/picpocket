# Justfile for PicPocket Android Project
# Build, test, and drive the sync layers:
#   lower  — rclone mount ↔ Drive, WebDAV ↔ rclone (infra_tests/)
#   full   — SAF → WebDAV → rclone → Drive (test_saf_to_drive.py)
#   sync   — all sync scenarios between multiple devices (scenarios/)

rclone_mount := "./tmp/gdrive-test"
compose_file := "./sync-tests/docker-compose.test.yml"
avd_name := "testPixel7"
sdk_root := env_var_or_default("ANDROID_HOME", env_var_or_default("ANDROID_SDK_ROOT", "/home/zun/.local/android-sdk"))

APP_NAME := "com.picpocket.app"
APK_PATH := "app/build/outputs/apk/debug/app-debug.apk"

_default: help

# List all available tasks (descriptions from the comment above each task)
help:
    @just --list

# Build the debug APK
build:
    @echo "Building..."
    ./gradlew assembleDebug
    @echo "Build completed!"

# Run the JVM unit tests
test:
    @echo "Running unit tests..."
    ./gradlew testDebugUnitTest
    @echo "Tests completed!"

# Build and install the debug APK on the connected device
install: build
    adb -d install -r {{APK_PATH}}
    @echo "Installed successfully!"

# Uninstall the app from the connected device
uninstall:
    adb uninstall {{APP_NAME}}
    @echo "Uninstalled!"

# Run Android lint
lint:
    ./gradlew lint

# Remove build artifacts
clean:
    rm -rf app/build .gradle
    @echo "Cleaned!"

# One-time setup of the testPixel7 AVD + system image (requires sdkmanager + avdmanager)
setup-emulator:
    @echo "Setting up emulator..."
    {{sdk_root}}/cmdline-tools/latest/bin/sdkmanager --sdk_root={{sdk_root}} "system-images;android-34;google_apis;x86_64"
    echo "no" | {{sdk_root}}/cmdline-tools/latest/bin/avdmanager create avd -n testPixel7 -k "system-images;android-34;google_apis;x86_64" -d pixel_7
    @echo "Emulator ready! Use 'just sync-run' to run the sync scenarios."

# Create the second AVD (testPixel7b) for two-device sync scenarios. Same
# system image + device definition as testPixel7, its own snapshot store.
setup-emulator-b:
    @echo "Setting up second emulator AVD (testPixel7b)..."
    echo "no" | {{sdk_root}}/cmdline-tools/latest/bin/avdmanager create avd -n testPixel7b -k "system-images;android-34;google_apis;x86_64" -d pixel_7
    @echo "testPixel7b ready! Bake its snapshot with 'just create-snapshot -- --avd testPixel7b --port 5556'."

# Bake a configured snapshot for an AVD (Nextcloud account + PIN 1234).
# Defaults bake testPixel7 on port 5554. For the second device:
#   just create-snapshot -- --avd testPixel7b --port 5556
# One-time provisioning step; not part of sync-run.
create-snapshot *args:
    python sync-tests/scripts/create_snapshot.py {{args}}

# Delete the testPixel7 AVD and its system image
cleanup-emulator:
    @echo "Cleaning up..."
    {{sdk_root}}/cmdline-tools/latest/bin/avdmanager delete avd -n testPixel7 || true
    yes | {{sdk_root}}/cmdline-tools/latest/bin/sdkmanager --sdk_root={{sdk_root}} --uninstall "system-images;android-34;google_apis;x86_64" || true
    @echo "Cleaned!"

# Start the Nextcloud + rclone test stack in the background (detached).
# Required by all test tasks; the suites also auto-start it via fixtures.
up:
    @docker compose -f {{compose_file}} up -d >/dev/null

# Full teardown of the test environment: compose down -v, unmount rclone,
# wipe Nextcloud data, and kill the emulator. Irreversible.
sync-clean:
    docker compose -f {{compose_file}} down -v --rmi all --remove-orphans
    fusermount -uz {{rclone_mount}} || true
    umount -l {{rclone_mount}} || true
    rm -rf {{rclone_mount}}
    docker run --rm -v /home/zun/dev/oc/pdfscanner/tmp:/tmp alpine sh -c "rm -rf /tmp/nc_data" 2>/dev/null || true
    python sync-tests/scripts/kill_emulator.py {{avd_name}} || true

# LOWER LAYER: rclone mount ↔ Drive and WebDAV ↔ rclone ↔ Drive.
# Runs sync-tests/infra_tests/ (fuse mount, webdav, cross-layer, edge cases)
# against the Nextcloud + rclone stack only — no emulator, no app.
# Used to gain confidence in the storage primitives.
# Extra args (e.g. -k <expr>, --maxfail=1) are passed to pytest.
lower-sync-run *args:
    cd sync-tests && .venv/bin/pytest infra_tests/ -v --tb=short --durations=5 {{args}}

# FULL STACK: SAF → WebDAV → rclone → Drive.
# Builds the APK, boots the emulator, and runs scenarios/test_saf_to_drive.py
# (4 tests: folder select, small/large file, reinstall). ~10 min.
# Extra args (e.g. --no-reset, --maxfail=1) are passed to pytest.
full-sync-run *args:
    ./gradlew assembleDebug
    python sync-tests/scripts/ensure_emulator.py
    cd sync-tests && systemd-inhibit --what=sleep -- .venv/bin/pytest scenarios/test_saf_to_drive.py -v {{args}}

# ALL SYNC SCENARIOS between multiple devices.
# Builds the APK, boots BOTH emulators (testPixel7@5554 + testPixel7b@5556)
# from their sync_test_ready snapshots, and runs every scenario in
# sync-tests/scenarios/ (incl. two-device tests). This is the main
# multi-device sync testing surface. Extra args are passed to pytest.
sync-run *args:
    ./gradlew assembleDebug
    python sync-tests/scripts/ensure_emulator.py
    cd sync-tests && systemd-inhibit --what=sleep -- .venv/bin/pytest -v -x {{args}}

# Run both primitive layers in sequence: lower-sync-run first (stack only),
# then full-sync-run (emulator + app). A confidence pass over the storage
# chain before the multi-device sync scenarios.
chain-run: lower-sync-run full-sync-run
