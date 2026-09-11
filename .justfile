# Justfile for PicPocket Android Project
# Build, test, and drive the sync layers:
#   infra-test            — rclone mount ↔ Drive, WebDAV ↔ rclone (infra_tests/)
#   saf-test              — focused SAF → WebDAV → rclone → Drive (test_saf_to_drive.py)
#   scenario-single       — 13 single-device scenarios, 2-way parallel (run_e2e.sh parallel)
#   scenario-two-device   — 5 two-device scenarios, serial (test_b, test_04, test_05, test_09, test_10)
#   scenario-test         — full suite: scenario-single then scenario-two-device
#   chain-test            — infra-test then saf-test in sequence

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
unit-test:
    @echo "Running unit tests..."
    ./gradlew testDebugUnitTest
    @echo "Tests completed!"

# Run instrumented Android tests on one emulator (testPixel7, emulator-5554).
# Always reboots from the sync_test_ready snapshot for a known state, then
# runs connectedDebugAndroidTest only on that device. Extra args (e.g.
# -Pandroid.testInstrumentationRunnerArguments.class=...) are passed to gradle.
android-test *args:
    EMULATOR_SERIAL=emulator-5554 python sync-tests/scripts/ensure_emulator.py
    ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest {{args}}

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
    @echo "Emulator ready! Use 'just scenario-test' to run the full sync suite."

# Create the second AVD (testPixel7b) for two-device sync scenarios. Same
# system image + device definition as testPixel7, its own snapshot store.
setup-emulator-b:
    @echo "Setting up second emulator AVD (testPixel7b)..."
    echo "no" | {{sdk_root}}/cmdline-tools/latest/bin/avdmanager create avd -n testPixel7b -k "system-images;android-34;google_apis;x86_64" -d pixel_7
    @echo "testPixel7b ready! Bake its snapshot with 'just create-snapshot -- --avd testPixel7b --port 5556'."

# Bake a configured snapshot for an AVD (Nextcloud account + PIN 1234).
# Defaults bake testPixel7 on port 5554. For the second device:
#   just create-snapshot -- --avd testPixel7b --port 5556
# One-time provisioning step; not part of scenario-test.
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

# Full teardown of the test environment: compose down -v, purge Drive + rclone
# mount, wipe Nextcloud data, caches and lock files, and kill the emulators. Irreversible.
sync-clean:
    docker compose -f {{compose_file}} down -v --rmi all --remove-orphans
    fusermount -uz {{rclone_mount}} || true
    umount -l {{rclone_mount}} || true
    rm -rf {{rclone_mount}}
    rclone purge gtest:PicPocketTest || true
    docker run --rm -v /home/zun/dev/oc/pdfscanner/tmp:/tmp alpine sh -c "rm -rf /tmp/nc_data" 2>/dev/null || true
    rm -rf tmp/rclone-vfs-cache
    rm -f tmp/.rclone_mount.lock tmp/.nextcloud_stack.lock
    python sync-tests/scripts/kill_emulator.py {{avd_name}} || true

# Clear rclone's on-disk vfs/vfsMeta cache remnants before a scenario run.
# The persistent cache can carry stale path-to-Drive-ID mappings after a purge
# or folder-ID change, which makes Nextcloud's mount-backed GETs 404 on files
# that exist. Only the cache is removed — the mount, stack and emulators stay
# up (the running daemon recreates cache entries on demand), so a re-run keeps
# its context and never needs a full sync-clean.
clear-rclone-cache:
    rm -rf tmp/rclone-vfs-cache

# STORAGE LAYER: rclone mount ↔ Drive and WebDAV ↔ rclone ↔ Drive.
# Runs sync-tests/infra_tests/ (fuse mount, webdav, cross-layer, edge cases)
# against the Nextcloud + rclone stack only — no emulator, no app.
# Used to gain confidence in the storage primitives.
# Extra args (e.g. -k <expr>, --maxfail=1) are passed to pytest.
infra-test *args:
    cd sync-tests && .venv/bin/pytest infra_tests/ -v --tb=short --durations=5 {{args}}

# FOCUSED SAF CHAIN: scenarios/test_saf_to_drive.py (4 tests: folder select,
# small/large file, reinstall). A fast single-device SUBSET of scenario-single
# (which already includes these tests) kept for quick iteration on the
# SAF -> WebDAV -> rclone -> Drive chain. ~10 min.
# Extra args (e.g. --no-reset, --maxfail=1) are passed to pytest.
saf-test *args:
    ./gradlew assembleDebug
    python sync-tests/scripts/ensure_emulator.py
    cd sync-tests && systemd-inhibit --what=sleep -- .venv/bin/pytest scenarios/test_saf_to_drive.py -v {{args}}

# SINGLE-DEVICE sync scenarios, 2-way parallel.
# Builds the APK, boots BOTH emulators, and runs the 13 single-device tests
# concurrently via scripts/run_e2e.sh parallel: worker-1 (5554/w1) runs
# test_01::test_a + the 4 SAF tests + push-after-edit (test_06) + interrupted
# sync (test_12), worker-2 (5556/w2) runs test_02 + test_03 + corrupt registry
# (test_07) + noop resync (test_08) + offline sync (test_11) + the batch
# import (test_01::test_batch). The five two-device scenarios (test_b,
# test_04, test_05, test_09, test_10) are NOT included: they need both
# emulators in one session, so they run under scenario-two-device. Launches
# both workers in the background and returns immediately; pass --wait to block
# until both workers have finished.
scenario-single *args: clear-rclone-cache
    ./gradlew assembleDebug
    python sync-tests/scripts/ensure_emulator.py
    bash scripts/run_e2e.sh parallel {{args}}

# TWO-DEVICE sync scenarios, serial.
# Builds the APK, boots BOTH emulators, and runs the five scenarios that need
# both devices in one session (rooted at PicPocketTest): test_01::test_b
# (download from another device), test_04 (encryption bootstrapping),
# test_05 (orphan), test_09 (passphrase rotation), test_10 (mutex contention).
# Complements scenario-single: together they cover every scenario exactly once.
# Launches pytest in the background and returns immediately; pass --wait to
# block until the session has finished.
scenario-two-device *args: clear-rclone-cache
    ./gradlew assembleDebug
    python sync-tests/scripts/ensure_emulator.py
    bash scripts/run_e2e.sh serial \
        scenarios/test_01_happy_path.py::TestHappyPath::test_b_downloads_from_other_device \
        scenarios/test_04_encryption.py \
        scenarios/test_05_orphan.py \
        scenarios/test_09_passphrase_change_reencrypt.py \
        scenarios/test_10_contention.py \
        {{args}}

# FULL SYNC SUITE: scenario-single (13 single-device tests in parallel) then
# scenario-two-device (5 two-device tests serial), in that order — the
# two-device stage needs both emulators, which the parallel workers occupy.
# Non-overlapping coverage of all 18 scenarios. Always blocks until the whole
# suite is done.
scenario-test *args: clear-rclone-cache
    ./gradlew assembleDebug
    python sync-tests/scripts/ensure_emulator.py
    bash scripts/run_e2e.sh parallel --wait {{args}}
    bash scripts/run_e2e.sh serial \
        scenarios/test_01_happy_path.py::TestHappyPath::test_b_downloads_from_other_device \
        scenarios/test_04_encryption.py \
        scenarios/test_05_orphan.py \
        scenarios/test_09_passphrase_change_reencrypt.py \
        scenarios/test_10_contention.py \
        --wait {{args}}

# Run both primitive layers in sequence: infra-test first (stack only), then
# saf-test (emulator + app). A confidence pass over the storage chain.
# Serialized explicitly in the body: just runs recipe dependencies in
# parallel, so a dependency list here would launch both at once.
chain-test:
    @just infra-test
    @just saf-test
