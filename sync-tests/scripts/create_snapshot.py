#!/usr/bin/env python3
"""
Boot an emulator in writable mode, configure everything, save as a snapshot.

Steps:
  1. Kill any running emulator
  2. Start Nextcloud server (Docker) if not running
  3. Create testuser in Nextcloud if not existing
  4. Boot <--avd> with -wipe-data (clean state) on <--port>
  5. Skip Android setup wizard, set PIN 1234
  6. Install Nextcloud Android APK
  10. Create Nextcloud account via deep link (nc://) + uiautomator2 dialog click
  11. Verify SAF DocumentsProvider is registered
  11. Close SAF picker and return to home screen
  12. Save snapshot as <--snapshot>
  13. Kill emulator

Run once per AVD so each has its own baked snapshot (snapshots are per-AVD):
    python sync-tests/scripts/create_snapshot.py                          # testPixel7
    python sync-tests/scripts/create_snapshot.py --avd testPixel7b --port 5556
"""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

import uiautomator2 as u2

THIS_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = THIS_DIR.parent.parent
SYNC_TESTS = PROJECT_ROOT / "sync-tests"
ANDROID_SDK_ROOT = Path(
    os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    or Path.home() / ".local/android-sdk"
)
NEXTCLOUD_APK = SYNC_TESTS / "nextcloud.apk"
NEXTCLOUD_USER = "testuser"
NEXTCLOUD_PASS = "testpass123"
COMPOSE_FILE = SYNC_TESTS / "docker-compose.test.yml"
NEXTCLOUD_CONTAINER = "sync-tests-nextcloud-1"


def run(cmd: list[str], timeout: int = 30, check: bool = True, input: str = None) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=check, input=input)


def adb(serial: str, *args: str, timeout: int = 30, check: bool = True) -> subprocess.CompletedProcess:
    return run(["adb", "-s", serial, *args], timeout=timeout, check=check)


def adb_shell(serial: str, command: str, timeout: int = 30) -> str:
    return adb(serial, "shell", command, timeout=timeout).stdout.strip()


def wait_for_boot(serial: str, timeout: int = 300):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if adb_shell(serial, "getprop sys.boot_completed") == "1":
            return
        time.sleep(3)
    raise TimeoutError("Emulator did not boot")


def _list_emulators() -> list[str]:
    result = run(["adb", "devices"], timeout=10)
    serials = []
    for line in result.stdout.strip().splitlines()[1:]:
        if "\tdevice" in line and line.split("\t")[0].startswith("emulator-"):
            serials.append(line.split("\t")[0])
    return serials


def kill_all_emulators():
    for serial in _list_emulators():
        run(["adb", "-s", serial, "emu", "kill"], check=False, timeout=10)
    time.sleep(3)


def _start_nextcloud_server():
    """Start Nextcloud Docker stack and ensure testuser exists."""
    print("[2/9] Starting Nextcloud server...")

    run(["docker", "compose", "-f", str(COMPOSE_FILE), "up", "-d"], timeout=60)

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

    run(["docker", "exec", NEXTCLOUD_CONTAINER, "php", "occ", "app:enable", "files_external"],
        check=False, timeout=30)

    print(f"  Creating/verifying testuser: {NEXTCLOUD_USER}")
    run(
        ["docker", "exec", "-e", f"OC_PASS={NEXTCLOUD_PASS}", NEXTCLOUD_CONTAINER,
         "php", "occ", "user:add", "--password-from-env", NEXTCLOUD_USER],
        check=False, timeout=30,
    )

    run(["docker", "exec", NEXTCLOUD_CONTAINER, "php", "occ",
         "config:system:set", "trusted_domains", "1", "--value=10.0.2.2"],
        check=False, timeout=10)

    print("  Configuring external storage mount...")
    run([
        "docker", "exec", NEXTCLOUD_CONTAINER, "php", "occ",
        "files_external:create", "PicPocketTest", "local", "null::null",
        "-c", "datadir=/mnt/gdrive"
    ], check=False, timeout=30)

    result = run(["docker", "exec", NEXTCLOUD_CONTAINER, "php", "occ",
                  "files_external:list", "--output=json"], check=False, timeout=15)
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


def _create_nextcloud_account(d: u2.Device, serial: str):
    """Create Nextcloud account via deep link (nc://) and click dialog confirmation."""
    uri = f"nc://login/server:http://10.0.2.2:8080&user:{NEXTCLOUD_USER}&password:{NEXTCLOUD_PASS}"

    script = f'am start -a android.intent.action.VIEW -d "{uri}" -n com.nextcloud.client/com.owncloud.android.authentication.DeepLinkLoginActivity'
    adb(serial, "shell", script, timeout=30)

    time.sleep(3)

    yes_btn = d(text="Yes")
    if yes_btn.wait(timeout=5):
        print("  Clicking 'Yes' on confirm login dialog...")
        yes_btn.click()
        time.sleep(2)
    else:
        for btn_text in ["Yes", "OK", "Allow", "Log in", "Confirm", "Continue", "Done"]:
            btn = d(text=btn_text)
            if btn.exists:
                print(f"  Clicking dialog button: {btn_text}")
                btn.click()
                time.sleep(2)
                break

    print("  Waiting for account creation...")
    time.sleep(10)

    accounts_output = subprocess.run(
        ["adb", "-s", d.serial, "shell", "dumpsys", "account", "com.nextcloud.client"],
        capture_output=True, text=True, timeout=30,
    ).stdout
    if f"{NEXTCLOUD_USER}@10.0.2.2:8080" not in accounts_output:
        raise RuntimeError("Nextcloud account not created!")
    print("  Nextcloud account created successfully!")


def verify_saf_provider(serial: str):
    """Confirm the Nextcloud DocumentsProvider is registered."""
    result = adb(serial, "shell", "dumpsys", "package", "com.nextcloud.client", timeout=15)
    if "org.nextcloud.documents" not in result.stdout:
        raise RuntimeError("Nextcloud DocumentsProvider not found in package dump")


def main():
    parser = argparse.ArgumentParser(description="Bake a configured emulator snapshot for an AVD.")
    parser.add_argument("--avd", default="testPixel7", help="AVD name to bake (default: testPixel7)")
    parser.add_argument("--port", default="5554", help="Emulator console port (default: 5554)")
    parser.add_argument("--snapshot", default="sync_test_ready", help="Snapshot name (default: sync_test_ready)")
    args = parser.parse_args()

    avd = args.avd
    port = args.port
    serial = f"emulator-{port}"
    snapshot = args.snapshot
    snapshot_dir = (
        Path.home() / ".android/avd" / f"{avd}.avd" / "snapshots" / snapshot
    )

    print(f"=== Creating {snapshot} snapshot for AVD {avd} (serial {serial}) ===")

    print("[1/9] Killing any running emulator...")
    kill_all_emulators()

    _start_nextcloud_server()

    print("[3/9] Booting emulator (cold boot, wipe data)...")
    emulator = ANDROID_SDK_ROOT / "emulator" / "emulator"
    if not emulator.exists():
        raise FileNotFoundError(f"Emulator not found at {emulator}")

    proc = subprocess.Popen(
        [str(emulator), "-avd", avd, "-port", port, "-no-window", "-noaudio",
         "-gpu", "swiftshader_indirect", "-wipe-data"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        adb(serial, "wait-for-device", timeout=120)
        wait_for_boot(serial, timeout=300)
        time.sleep(10)

        adb(serial, "shell", "settings", "put", "global", "device_provisioned", "1",
            check=False, timeout=10)
        adb(serial, "shell", "settings", "put", "secure", "user_setup_complete", "1",
            check=False, timeout=10)
        time.sleep(2)

        print("[4/9] Installing Nextcloud APK...")
        if not NEXTCLOUD_APK.exists():
            raise FileNotFoundError(f"Nextcloud APK not found at {NEXTCLOUD_APK}")
        adb(serial, "install", "-r", "-g", str(NEXTCLOUD_APK), timeout=120)
        time.sleep(2)

        print("[5/9] Creating Nextcloud account via deep link...")
        d = u2.connect(serial)
        _create_nextcloud_account(d, serial)

        print("[6/9] Verifying SAF DocumentsProvider...")
        verify_saf_provider(serial)
        print("  Nextcloud DocumentsProvider OK")

        print("[7/9] Closing SAF picker and returning to home screen...")
        adb(serial, "shell", "am", "start", "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.HOME", check=False)
        time.sleep(2)
        adb(serial, "shell", "am", "force-stop", "com.nextcloud.client", check=False)
        time.sleep(1)

        print("[8/9] Setting PIN 1234...")
        adb(serial, "shell", "locksettings", "set-pin", "1234", timeout=15)
        time.sleep(1)

        print(f"[9/9] Saving snapshot as {snapshot}...")
        adb(serial, "emu", "avd", "snapshot", "save", snapshot, timeout=30)
        print("  Snapshot saved!")

        print("\n=== Snapshot creation complete! ===")
        if snapshot_dir.exists():
            size = sum(f.stat().st_size for f in snapshot_dir.rglob("*") if f.is_file())
            print(f"Snapshot size: {size / 1024 / 1024:.0f} MB")

    finally:
        print("Killing emulator...")
        for emu_serial in _list_emulators():
            run(["adb", "-s", emu_serial, "emu", "kill"], check=False, timeout=10)
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    main()
