#!/usr/bin/env python3
"""Debug sync trigger - check what happens when Sync Now is clicked.

Focus on:
1. Does the app actually sync via SAF on a fresh test (not snapshot restore)?
2. What does the full logcat look like around the sync click?
3. Is the Clickable parent of 'Sync Now' Text actually receiving the touch?
"""

import logging
import subprocess
import sys
import time
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logger = logging.getLogger(__name__)

sys.path.insert(0, str(Path(__file__).parent.parent))
from devices.ui import UiDevice, APP_PACKAGE
from infra.nextcloud import start as nc_start, stop as nc_stop
from devices.pdf_utils import generate_and_push

EMU_SERIAL = "emulator-5554"

def full_logcat(adb, lines=500):
    result = subprocess.run(
        ["adb", "-s", EMU_SERIAL, "logcat", "-d", "-t", str(lines)],
        capture_output=True, text=True, timeout=30
    )
    return result.stdout

def filtered_logcat(adb, patterns, lines=500):
    raw = full_logcat(adb, lines)
    return [l for l in raw.splitlines()
            if any(p in l for p in patterns)]

def dump_uixml(emu):
    """Dump current UI XML for analysis."""
    return emu.d.dump_hierarchy()

def main():
    logger.info("=" * 60)
    logger.info("DEBUG: Sync trigger investigation")
    logger.info("=" * 60)

    # Start infra
    logger.info("Starting Nextcloud stack...")
    nc_start()

    # Connect to emulator
    logger.info("Connecting to emulator...")
    emu = UiDevice(EMU_SERIAL)

    # Clear app data for clean state
    logger.info("Clearing app data...")
    subprocess.run(
        ["adb", "-s", EMU_SERIAL, "shell", "pm", "clear", APP_PACKAGE],
        capture_output=True, timeout=15
    )
    time.sleep(2)

    # Generate and push test PDF
    logger.info("Generating and pushing test PDF...")
    generate_and_push(emu.adb, "debug-sync", pages=2)

    # Open app
    logger.info("Opening app...")
    emu.open_app()
    time.sleep(2)

    # Import the PDF (this triggers SAF flow)
    logger.info("Importing PDF...")
    emu.import_pdf("debug-sync.pdf")
    time.sleep(3)

    # Go to settings → Sync
    logger.info("Going to Sync screen...")
    prev = emu.d.app_current()
    logger.info("Current app before home: %s", prev)
    emu._go_home(timeout=10.0)
    emu.open_settings()
    sync_text = emu.d(text="Sync")
    logger.info("Sync text visible: %s, bounds: %s", sync_text.exists,
                sync_text.info.get("bounds") if sync_text.exists else "N/A")
    sync_text.click()
    time.sleep(2)

    # Dump the Sync screen UI
    logger.info("Dumping Sync screen UI hierarchy...")
    xml = dump_uixml(emu)
    outfile = "/home/zun/dev/oc/pdfscanner/sync-tests/debug/ui_dump.xml"
    with open(outfile, "w") as f:
        f.write(xml)
    logger.info("UI dump saved to %s", outfile)

    # Check if toggle needs clicking
    sync_toggle = emu.d(description="Toggle sync")
    hint = emu.d(text="Enable sync to start syncing")
    logger.info("Toggle exists: %s", sync_toggle.exists)
    logger.info("Hint text exists: %s", hint.exists)

    if sync_toggle.exists and hint.exists:
        logger.info("Clicking sync toggle (ADB tap)...")
        b = sync_toggle.info.get("bounds", {})
        tx = (b["left"] + b["right"]) // 2
        ty = (b["top"] + b["bottom"]) // 2
        logger.info("Toggle center: (%d, %d)", tx, ty)
        emu.adb.shell(f"input tap {tx} {ty}")
        time.sleep(2)

    # Check Sync Now button
    sync_now = emu.d(text="Sync Now")
    logger.info("Sync Now exists: %s", sync_now.exists)
    if sync_now.exists:
        info = sync_now.info
        logger.info("Sync Now info: enabled=%s clickable=%s bounds=%s",
                    info.get("enabled"), info.get("clickable"), info.get("bounds"))

        # Find the clickable parent by checking the accessibility tree
        logger.info("Searching for clickable elements containing 'Sync Now'...")
        tree = dump_uixml(emu)
        import re
        # Find lines around Sync Now
        lines = tree.splitlines()
        for i, line in enumerate(lines):
            if 'Sync Now' in line or 'sync' in line.lower():
                start = max(0, i-3)
                end = min(len(lines), i+4)
                for j in range(start, end):
                    logger.info("  [%d] %s", j, lines[j].strip())

        # Tap Sync Now
        b = info.get("bounds", {})
        x = (b["left"] + b["right"]) // 2
        y = (b["top"] + b["bottom"]) // 2
        logger.info("Tapping Sync Now at (%d, %d)...", x, y)

        # Get full logcat BEFORE tap
        emu.adb.shell("logcat -c")
        time.sleep(0.5)

        # Tap
        emu.adb.shell(f"input tap {x} {y}")
        time.sleep(3)

        # Get logcat AFTER tap - get ALL lines (no filtering)
        log_output = full_logcat(emu.adb, 2000)
        outfile2 = "/home/zun/dev/oc/pdfscanner/sync-tests/debug/logcat_after_sync.txt"
        with open(outfile2, "w") as f:
            f.write(log_output)
        logger.info("Full logcat saved to %s (%d bytes, %d lines)",
                    outfile2, len(log_output), len(log_output.splitlines()))

        # Filter for app + sync related lines
        app_patterns = ["picpocket", APP_PACKAGE, "performSync", "SyncMutex",
                        "sync-lock", "ReadFileRemote", "RefreshFolder",
                        "PicPocketTest", "SAF", "DocumentProvider"]
        app_lines = [l for l in log_output.splitlines()
                     if any(p.lower() in l.lower() for p in app_patterns)]
        logger.info("App/sync-related logcat lines (%d total):", len(app_lines))
        for line in app_lines:
            logger.info("  %s", line)

        if not app_lines:
            logger.warning("NO APP LOGS FOUND IN LOGCAT!")
            logger.warning("Checking all lines containing 'picpocket' or 'com.picpocket'...")
            all_app = [l for l in log_output.splitlines()
                       if "picpocket" in l.lower()]
            for line in all_app:
                logger.info("  %s", line)

    else:
        logger.warning("Sync Now not found!")

    # Check if oracle can reach nextcloud
    from oracle.nextcloud_oracle import NextcloudOracle
    oracle = NextcloudOracle()
    try:
        files = oracle.list_files()
        logger.info("Oracle found %d files on Nextcloud", len(files))
        for f in files:
            logger.info("  - %s", f)
    except Exception as e:
        logger.error("Oracle check failed: %s", e)

    # Cleanup
    logger.info("Debug complete - leaving infra running for inspection")
    logger.info("Run: docker compose -f sync-tests/docker-compose.test.yml down -v")

if __name__ == "__main__":
    main()
