#!/usr/bin/env python3
"""
Ensure an emulator is running and booted.
Boots testPixel7 AVD from sync_test_ready snapshot, always from a fresh
kill+reboot so the device is in a guaranteed known state.
"""

import os
import subprocess
import time
from pathlib import Path

AVD_NAME = "testPixel7"
ANDROID_SDK_ROOT = Path(
    os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    or Path.home() / ".local/android-sdk"
)


def _adb(*args, timeout: int = 30) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)


def _list_emulators() -> list[str]:
    result = _adb("devices", timeout=10)
    serials = []
    for line in result.stdout.strip().splitlines()[1:]:
        if "\tdevice" in line and line.split("\t")[0].startswith("emulator-"):
            serials.append(line.split("\t")[0])
    return serials


def kill_all_emulators() -> None:
    for serial in _list_emulators():
        _adb("-s", serial, "emu", "kill", timeout=10)
        print(f"Killed {serial}")
    subprocess.run(["pkill", "-f", "qemu-system"], capture_output=True, text=True, timeout=10)
    time.sleep(3)


def wait_for_boot(serial: str, timeout: int = 180) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = _adb("-s", serial, "shell", "getprop", "sys.boot_completed", timeout=10)
        if result.stdout.strip() == "1":
            return
        time.sleep(3)
    raise TimeoutError(f"Emulator {serial} did not boot within {timeout}s")


def boot_from_snapshot() -> str:
    """Kill any running emulator, boot testPixel7 from sync_test_ready, return serial."""
    kill_all_emulators()

    emulator = ANDROID_SDK_ROOT / "emulator" / "emulator"
    if not emulator.exists():
        raise FileNotFoundError(
            f"Emulator binary not found at {emulator}. "
            f"Set ANDROID_SDK_ROOT or ANDROID_HOME."
        )

    print("Booting emulator from sync_test_ready snapshot...")
    subprocess.Popen(
        [
            str(emulator),
            "-avd", AVD_NAME,
            "-port", "5554",
            "-no-window", "-noaudio",
            "-gpu", "swiftshader_indirect",
            "-read-only",
            "-snapshot", "sync_test_ready",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    print("Waiting for device...")
    _adb("-s", "emulator-5554", "wait-for-device", timeout=180)

    serial = _list_emulators()
    if not serial:
        raise RuntimeError("No emulator serial detected after boot")
    serial = serial[0]
    print(f"Device serial: {serial}")

    print("Waiting for boot...")
    wait_for_boot(serial)

    print("Setting lock screen PIN...")
    _adb("-s", serial, "shell", "locksettings", "set-pin", "1234", timeout=15)

    print("Unlocking keyguard...")
    _adb("-s", serial, "shell", "wm", "dismiss-keyguard", timeout=15)

    print(f"Emulator ready: {serial}")
    return serial


def main():
    boot_from_snapshot()


if __name__ == "__main__":
    main()
