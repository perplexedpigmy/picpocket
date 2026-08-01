#!/usr/bin/env python3
"""
Boot emulator in writable mode, configure everything, save as sync_test_ready.

Steps:
  1. Kill any running emulator
  2. Start Nextcloud server (Docker) if not running
  3. Create testuser in Nextcloud if not existing
  4. Boot testPixel7 with -wipe-data (clean state)
  5. Skip Android setup wizard, set PIN 1234
  6. Install Nextcloud Android APK
  10. Create Nextcloud account via deep link (nc://) + uiautomator2 dialog click
  11. Verify SAF DocumentsProvider is registered
  11. Close SAF picker and return to home screen
  12. Save snapshot as sync_test_ready
  13. Kill emulator
"""

import os
import re
import subprocess
import sys
import time
from pathlib import Path

import uiautomator2 as u2

THIS_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = THIS_DIR.parent.parent
SYNC_TESTS = PROJECT_ROOT / "sync-tests"
AVD_NAME = "testPixel7"
ANDROID_SDK_ROOT = Path(
    os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    or Path.home() / ".local/android-sdk"
)
SNAPSHOT_DIR = (
    Path.home() / ".android/avd" / f"{AVD_NAME}.avd" / "snapshots" / "sync_test_ready"
)
NEXTCLOUD_APK = SYNC_TESTS / "nextcloud.apk"
NEXTCLOUD_USER = "testuser"
NEXTCLOUD_PASS = "testpass123"
COMPOSE_FILE = SYNC_TESTS / "docker-compose.test.yml"
NEXTCLOUD_CONTAINER = "sync-tests-nextcloud-1"


def run(cmd: list[str], timeout: int = 30, check: bool = True, input: str = None) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=check, input=input)


def adb_shell(command: str) -> str:
    return run(["adb", "shell", command]).stdout.strip()


def wait_for_boot(timeout: int = 120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if adb_shell("getprop sys.boot_completed") == "1":
            return
        time.sleep(3)
    raise TimeoutError("Emulator did not boot")


def _list_emulators() -> list[str]:
    result = run(["adb", "devices"], timeout=10)
    serials = []
    for line in result.stdout.strip().splitlines()[1:]:
        if "emulator" in line and "device" in line:
            serials.append(line.split()[0])
    return serials


def _start_nextcloud_server():
    """Start Nextcloud Docker stack and ensure testuser exists."""
    print("[2/9] Starting Nextcloud server...")
    
    # Start Docker Compose stack
    run(["docker", "compose", "-f", str(COMPOSE_FILE), "up", "-d"], timeout=60)
    
    # Wait for Nextcloud to be healthy
    print("  Waiting for Nextcloud to be healthy...")
    deadline = time.time() + 120
    while time.time() < deadline:
        result = run(
            ["docker", "exec", NEXTCLOUD_CONTAINER, "php", "occ", "status"],
            check=False, timeout=15,
        )
        if "installed: true" in result.stdout:
            print("  Nextcloud is healthy")
            break
        time.sleep(2)
    else:
        raise TimeoutError("Nextcloud not healthy after 120s")
    
    # Enable files_external app
    run(["docker", "exec", NEXTCLOUD_CONTAINER, "php", "occ", "app:enable", "files_external"], 
        check=False, timeout=30)
    
    # Create testuser if not exists
    print(f"  Creating/verifying testuser: {NEXTCLOUD_USER}")
    run(
        ["docker", "exec", "-e", f"OC_PASS={NEXTCLOUD_PASS}", NEXTCLOUD_CONTAINER,
         "php", "occ", "user:add", "--password-from-env", NEXTCLOUD_USER],
        check=False, timeout=30,
    )
    
    # Configure trusted domains for emulator access (10.0.2.2)
    run(["docker", "exec", NEXTCLOUD_CONTAINER, "php", "occ", 
         "config:system:set", "trusted_domains", "1", "--value=10.0.2.2"], 
        check=False, timeout=10,
    )
    
    # Configure local external storage mount (rclone -> Google Drive) if not exists
    # This is needed for the PicPocketTest folder
    print("  Configuring external storage mount...")
    run([
        "docker", "exec", NEXTCLOUD_CONTAINER, "php", "occ",
        "files_external:create", "PicPocketTest", "local", "null::null",
        "-c", "datadir=/mnt/gdrive"
    ], check=False, timeout=30)
    
    # Get mount ID and assign to testuser
    result = run(["docker", "exec", NEXTCLOUD_CONTAINER, "php", "occ", 
                  "files_external:list", "--output=json"], check=False, timeout=15)
    import json
    mounts = json.loads(result.stdout) if result.returncode == 0 else []
    for m in mounts:
        if m.get("mount_point") == "/PicPocketTest":
            mount_id = m["mount_id"]
            run(["docker", "exec", NEXTCLOUD_CONTAINER, "php", "occ",
                 "files_external:applicable", str(mount_id), "--add-user", NEXTCLOUD_USER],
                check=False, timeout=15)
            print(f"  Assigned mount {mount_id} to {NEXTCLOUD_USER}")
            break
    
    print("  Nextcloud server ready")


def _create_nextcloud_account(d: u2.Device):
    """Create Nextcloud account via deep link (nc://) and click dialog confirmation."""
    # Deep link URI for Nextcloud account creation
    # Format: nc://login/server:<url>&user:<username>&password:<password>
    uri = f"nc://login/server:http://10.0.2.2:8080&user:{NEXTCLOUD_USER}&password:{NEXTCLOUD_PASS}"
    
    # Launch DeepLinkLoginActivity with the nc:// URI
    script = f'am start -a android.intent.action.VIEW -d "{uri}" -n com.nextcloud.client/com.owncloud.android.authentication.DeepLinkLoginActivity'
    run(["adb", "shell"], input=script, timeout=30)
    
    # Wait for the confirmation dialog to appear
    time.sleep(3)
    
    # Click the "Yes" button in the "Confirm login" dialog
    # Dialog text: "Confirm login", "Do you want to log in with testuser to 10.0.2.2:8080?", buttons: "No", "Yes"
    yes_btn = d(text="Yes")
    if yes_btn.wait(timeout=5):
        print("  Clicking 'Yes' on confirm login dialog...")
        yes_btn.click()
        time.sleep(2)
    else:
        # Fallback: try other common button texts
        for btn_text in ["Yes", "OK", "Allow", "Log in", "Confirm", "Continue", "Done"]:
            btn = d(text=btn_text)
            if btn.exists:
                print(f"  Clicking dialog button: {btn_text}")
                btn.click()
                time.sleep(2)
                break
    
    # Wait for account creation to complete
    print("  Waiting for account creation...")
    time.sleep(10)
    
    # Verify account was created
    accounts_output = adb_shell("dumpsys account com.nextcloud.client")
    if f"{NEXTCLOUD_USER}@10.0.2.2:8080" not in accounts_output:
        raise RuntimeError("Nextcloud account not created!")
    print("  ✅ Nextcloud account created successfully!")


def verify_saf_provider():
    """Confirm the Nextcloud DocumentsProvider is registered."""
    result = run(["adb", "shell", "dumpsys", "package", "com.nextcloud.client"], timeout=15)
    if "org.nextcloud.documents" not in result.stdout:
        raise RuntimeError("Nextcloud DocumentsProvider not found in package dump")


def _list_emulators() -> list[str]:
    result = run(["adb", "devices"], timeout=10)
    serials = []
    for line in result.stdout.strip().splitlines()[1:]:
        if "emulator" in line and "device" in line:
            serials.append(line.split()[0])
    return serials


def wait_for_boot(timeout: int = 120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if adb_shell("getprop sys.boot_completed") == "1":
            return
        time.sleep(3)
    raise TimeoutError("Emulator did not boot")


def adb_shell(command: str) -> str:
    return run(["adb", "shell", command]).stdout.strip()


def verify_saf_provider():
    """Confirm the Nextcloud DocumentsProvider is registered."""
    result = run(["adb", "shell", "dumpsys", "package", "com.nextcloud.client"], timeout=15)
    if "org.nextcloud.documents" not in result.stdout:
        raise RuntimeError("Nextcloud DocumentsProvider not found in package dump")


def run(cmd: list[str], timeout: int = 30, check: bool = True, input: str = None) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=check, input=input)


def main():
    print("=== Creating sync_test_ready snapshot ===")

    # Kill any running emulator
    print("[1/9] Killing any running emulator...")
    for serial in _list_emulators():
        run(["adb", "-s", serial, "emu", "kill"], check=False)
    time.sleep(3)

    # Start Nextcloud server and ensure testuser exists
    _start_nextcloud_server()

    # Boot emulator writable (cold boot, wipe data for clean state)
    print("[3/9] Booting emulator (cold boot, wipe data)...")
    emulator = ANDROID_SDK_ROOT / "emulator" / "emulator"
    if not emulator.exists():
        raise FileNotFoundError(f"Emulator not found at {emulator}")

    proc = subprocess.Popen(
        [str(emulator), "-avd", AVD_NAME, "-no-window", "-noaudio",
         "-gpu", "swiftshader_indirect", "-wipe-data"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        run(["adb", "wait-for-device"], timeout=120)
        wait_for_boot(timeout=300)
        time.sleep(10)

        # Skip Android setup wizard on clean boot
        run(["adb", "shell", "settings", "put", "global", "device_provisioned", "1"],
            check=False, timeout=10)
        run(["adb", "shell", "settings", "put", "secure", "user_setup_complete", "1"],
            check=False, timeout=10)
        time.sleep(2)

        # Install Nextcloud APK
        print("[4/9] Installing Nextcloud APK...")
        if not NEXTCLOUD_APK.exists():
            raise FileNotFoundError(f"Nextcloud APK not found at {NEXTCLOUD_APK}")
        run(["adb", "install", "-r", "-g", str(NEXTCLOUD_APK)], timeout=120)
        time.sleep(2)

        # Create Nextcloud account via deep link + uiautomator2
        print("[5/9] Creating Nextcloud account via deep link...")
        d = u2.connect()
        _create_nextcloud_account(d)

        # Verify SAF provider
        print("[6/9] Verifying SAF DocumentsProvider...")
        verify_saf_provider()
        print("  Nextcloud DocumentsProvider OK")

        # Close SAF picker and return to home screen (critical for clean snapshot)
        print("[7/9] Closing SAF picker and returning to home screen...")
        run(["adb", "shell", "am", "start", "-a", "android.intent.action.MAIN",
             "-c", "android.intent.category.HOME"], check=False)
        time.sleep(2)
        run(["adb", "shell", "am", "force-stop", "com.nextcloud.client"], check=False)
        time.sleep(1)

        # Set PIN last (locks screen, uiautomator2 won't work after this)
        print("[8/9] Setting PIN 1234...")
        run(["adb", "shell", "locksettings", "set-pin", "1234"], timeout=15)
        time.sleep(1)

        # Save snapshot
        print("[9/9] Saving snapshot as sync_test_ready...")
        run(["adb", "emu", "avd", "snapshot", "save", "sync_test_ready"], timeout=30)
        print("  Snapshot saved!")

        print("\n=== Snapshot creation complete! ===")
        if SNAPSHOT_DIR.exists():
            size = sum(f.stat().st_size for f in SNAPSHOT_DIR.rglob("*") if f.is_file())
            print(f"Snapshot size: {size / 1024 / 1024:.0f} MB")

    finally:
        # Kill emulator
        print("Killing emulator...")
        for serial in _list_emulators():
            run(["adb", "-s", serial, "emu", "kill"], check=False)
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    main()