#!/usr/bin/env python3
"""Debug: check if Sync Now click produces ANY app log output."""

import logging
import subprocess
import sys
import time
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logger = logging.getLogger(__name__)

sys.path.insert(0, str(Path(__file__).parent.parent))
from devices.ui import UiDevice, APP_PACKAGE
from devices.adb import AdbDevice
from infra import nextcloud

EMU_SERIAL = "emulator-5554"

def main():
    # Start infra if not running
    # (using existing running containers or starting fresh)
    logger.info("=== Step 1: Ensure Drive configured ===")
    emu = UiDevice(EMU_SERIAL)
    adb = AdbDevice(EMU_SERIAL)

    # Check current app - should be at sync screen from previous test
    current = emu.d.app_current()
    logger.info("Current app: %s", current)

    # Navigate to sync screen
    if current.get("package") != APP_PACKAGE:
        emu.open_app()
    emu._go_home(timeout=10.0)
    emu.open_settings()
    emu.d(text="Sync").click()
    time.sleep(2)

    # Check what's on screen
    xml = emu.d.dump_hierarchy()
    if "Select Folder" in xml:
        logger.warning("Drive not configured! Running ensure_drive_configured...")
        # Need to configure drive first
        emu.d.press("back")
        emu.d.press("back")
        emu.ensure_drive_configured()
        time.sleep(2)
        emu.open_settings()
        emu.d(text="Sync").click()
        time.sleep(2)

    # Now check sync screen
    sync_now = emu.d(text="Sync Now")
    logger.info("Sync Now: exists=%s", sync_now.exists)
    if sync_now.exists:
        logger.info("Sync Now bounds: %s", sync_now.info.get("bounds"))
        logger.info("Sync Now enabled: %s", sync_now.info.get("enabled"))

    # Check toggle
    toggle = emu.d(description="Toggle sync")
    logger.info("Toggle: exists=%s, checked=%s",
                toggle.exists,
                toggle.info.get("checked") if toggle.exists else "N/A")

    # Clear logcat before click
    logger.info("Clearing logcat...")
    adb.shell("logcat -c -b all")
    time.sleep(1)

    # Click Sync Now
    logger.info("=== Step 2: Click Sync Now ===")
    sync_now.click()
    time.sleep(5)

    # Dump full logcat after click
    logger.info("=== Step 3: Capture logcat ===")
    result = subprocess.run(
        ["adb", "-s", EMU_SERIAL, "logcat", "-d", "-t", "500", "-b", "main"],
        capture_output=True, text=True, timeout=15
    )
    log_output = result.stdout

    # Filter for ANY app-related lines
    app_lines = []
    for line in log_output.splitlines():
        if APP_PACKAGE in line or "picpocket" in line.lower():
            app_lines.append(line)

    logger.info("PicPocket log lines (%d):", len(app_lines))
    for line in app_lines:
        logger.info("  %s", line)

    if not app_lines:
        logger.warning("NO picpocket app log lines found!")
        # Check logs from the system buffers too
        result2 = subprocess.run(
            ["adb", "-s", EMU_SERIAL, "logcat", "-d", "-t", "500", "-b", "system"],
            capture_output=True, text=True, timeout=15
        )
        system_lines = []
        for line in result2.stdout.splitlines():
            if APP_PACKAGE in line or "picpocket" in line.lower():
                system_lines.append(line)
        logger.info("PicPocket system log lines (%d):", len(system_lines))
        for line in system_lines:
            logger.info("  %s", line)

    # Also check if the app process is running
    running = adb.shell("ps | grep picpocket || true")
    logger.info("Running picpocket processes:\n%s", running)

    # Try the ADB tap approach too
    logger.info("=== Step 4: Try ADB tap ===")
    b = sync_now.info.get("bounds", {})
    x = (b["left"] + b["right"]) // 2
    y = (b["top"] + b["bottom"]) // 2
    logger.info("ADB tap at (%d, %d)...", x, y)
    adb.shell("logcat -c -b all")
    time.sleep(1)
    adb.shell(f"input tap {x} {y}")
    time.sleep(5)

    result3 = subprocess.run(
        ["adb", "-s", EMU_SERIAL, "logcat", "-d", "-t", "500", "-b", "main"],
        capture_output=True, text=True, timeout=15
    )
    tap_lines = [l for l in result3.stdout.splitlines()
                 if APP_PACKAGE in l or "picpocket" in l.lower() or "performSync" in l]
    logger.info("After ADB tap - picpocket/performSync lines (%d):", len(tap_lines))
    for line in tap_lines:
        logger.info("  %s", line)

    logger.info("=== DONE ===")

if __name__ == "__main__":
    main()
