#!/usr/bin/env python3
"""Kill all running emulators with the given AVD name."""

import subprocess
import sys

AVD_NAME = sys.argv[1] if len(sys.argv) > 1 else "testPixel7"


def main():
    result = subprocess.run(
        ["adb", "devices"], capture_output=True, text=True, timeout=10
    )
    for line in result.stdout.strip().splitlines()[1:]:
        if "\tdevice" not in line:
            continue
        serial = line.split("\t")[0]
        if not serial.startswith("emulator-"):
            continue
        name = subprocess.run(
            ["adb", "-s", serial, "emu", "avd", "name"],
            capture_output=True, text=True, timeout=5,
        ).stdout.strip().splitlines()[0] if serial else ""
        if AVD_NAME in name:
            subprocess.run(
                ["adb", "-s", serial, "emu", "kill"],
                timeout=5, capture_output=True,
            )
            print(f"Killed {serial} ({AVD_NAME})")


if __name__ == "__main__":
    main()
