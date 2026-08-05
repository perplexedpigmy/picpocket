#!/usr/bin/env python3
"""
Ensure the test emulators are running and booted.

Boots two AVDs from their sync_test_ready snapshots, always from a fresh
kill+reboot so each device is in a guaranteed known state:
  A: testPixel7  on console port 5554 (adb serial emulator-5554)
  B: testPixel7b on console port 5556 (adb serial emulator-5556)
"""

import os
import subprocess
import time
from pathlib import Path

DEVICES = [
    {"avd": "testPixel7", "port": "5554"},
    {"avd": "testPixel7b", "port": "5556"},
]
SNAPSHOT = "sync_test_ready"

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


def _wait_all_qemu_down(timeout: int = 90) -> None:
    """Wait until no qemu-system process remains (releases console ports)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = subprocess.run(["pgrep", "-f", "qemu-system"], capture_output=True, text=True, timeout=10)
        if not out.stdout.strip():
            return
        time.sleep(2)
    subprocess.run(["pkill", "-9", "-f", "qemu-system"], capture_output=True, text=True, timeout=10)
    time.sleep(5)


def kill_all_emulators() -> None:
    for serial in _list_emulators():
        _adb("-s", serial, "emu", "kill", timeout=10)
        print(f"Killed {serial}")
    subprocess.run(["pkill", "-f", "qemu-system"], capture_output=True, text=True, timeout=10)
    _wait_all_qemu_down()


def wait_for_boot(serial: str, timeout: int = 180) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = _adb("-s", serial, "shell", "getprop", "sys.boot_completed", timeout=10)
        if result.stdout.strip() == "1":
            return
        time.sleep(3)
    raise TimeoutError(f"Emulator {serial} did not boot within {timeout}s")


def _emulator_binary() -> Path:
    emulator = ANDROID_SDK_ROOT / "emulator" / "emulator"
    if not emulator.exists():
        raise FileNotFoundError(
            f"Emulator binary not found at {emulator}. "
            f"Set ANDROID_SDK_ROOT or ANDROID_HOME."
        )
    return emulator


def boot(avd: str, port: str, snapshot: str = SNAPSHOT) -> str:
    """Boot a single AVD from its snapshot, return its serial."""
    emulator = _emulator_binary()
    serial = f"emulator-{port}"

    print(f"Booting {avd} (serial {serial}) from snapshot {snapshot}...")
    subprocess.Popen(
        [
            str(emulator),
            "-avd", avd,
            "-port", port,
            "-no-window", "-noaudio",
            "-gpu", "swiftshader_indirect",
            "-read-only",
            "-snapshot", snapshot,
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    _adb("-s", serial, "wait-for-device", timeout=180)
    wait_for_boot(serial)

    print(f"Setting lock screen PIN...")
    _adb("-s", serial, "shell", "locksettings", "set-pin", "1234", timeout=15)

    print(f"Unlocking keyguard...")
    _adb("-s", serial, "shell", "wm", "dismiss-keyguard", timeout=15)

    print(f"Emulator ready: {serial}")
    return serial


def _logs_dir() -> Path:
    logs = (Path(__file__).resolve().parent.parent) / "tmp"
    logs.mkdir(parents=True, exist_ok=True)
    return logs


def boot_devices() -> list[str]:
    """Kill any running emulator, boot all DEVICES in parallel, return serials."""
    kill_all_emulators()

    # Launch every emulator process first, then wait on each boot. Starting
    # them together cuts total wall time versus a serial boot. Keep a per-port
    # log so early-exits are diagnosable, and detect them instead of hanging.
    logs = _logs_dir()
    spawned = []
    for device in DEVICES:
        emulator = _emulator_binary()
        serial = f"emulator-{device['port']}"
        print(f"Launching {device['avd']} ({serial})...")
        log_file = open(logs / f"emulator-{device['port']}.log", "w")
        proc = subprocess.Popen(
            [
                str(emulator),
                "-avd", device["avd"],
                "-port", device["port"],
                "-no-window", "-noaudio",
                "-gpu", "swiftshader_indirect",
                "-read-only",
                "-snapshot", SNAPSHOT,
            ],
            stdout=log_file,
            stderr=subprocess.STDOUT,
        )
        spawned.append((serial, proc, log_file))

    serials = []
    for serial, proc, log_file in spawned:
        _wait_for_launched(proc, serial, log_file)
        _adb("-s", serial, "wait-for-device", timeout=180)
        wait_for_boot(serial)
        print(f"Setting lock screen PIN...")
        _adb("-s", serial, "shell", "locksettings", "set-pin", "1234", timeout=15)
        print(f"Unlocking keyguard...")
        _adb("-s", serial, "shell", "wm", "dismiss-keyguard", timeout=15)
        serials.append(serial)
        print(f"Emulator ready: {serial}")

    return serials


def _wait_for_launched(proc: subprocess.Popen, serial: str, log_file) -> None:
    """Give the emulator a moment to start; bail loudly if it died immediately."""
    deadline = time.time() + 20
    while time.time() < deadline and proc.poll() is None:
        time.sleep(1)
    if proc.poll() is not None:
        log_file.flush()
        log_file.seek(0)
        tail = "\n".join(log_file.read().splitlines()[-25:])
        log_file.close()
        raise RuntimeError(
            f"Emulator {serial} exited early (rc={proc.returncode}).\n"
            f"Boot log tail:\n{tail}"
        )


def boot_from_snapshot() -> str:
    """Single-device entry point: kill any running emulator, boot testPixel7,
    return its serial. This is the default session boot (one device); the
    second device is only booted on demand by device B (see boot_device_b)."""
    kill_all_emulators()
    return boot(DEVICES[0]["avd"], DEVICES[0]["port"])


def boot_device_b() -> str:
    """Boot testPixel7b (device B) on port 5556 WITHOUT disturbing device A.

    Called lazily the first time a two-device test asks for device B, so
    single-device tests never drag a second emulator into the session.
    """
    return boot(DEVICES[1]["avd"], DEVICES[1]["port"])


def main():
    boot_from_snapshot()


if __name__ == "__main__":
    main()
