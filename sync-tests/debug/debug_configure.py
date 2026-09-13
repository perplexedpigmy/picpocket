#!/usr/bin/env python3
"""Debug ensure_drive_configured flow step by step."""

import logging
import sys
import time
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logger = logging.getLogger(__name__)

sys.path.insert(0, str(Path(__file__).parent.parent))
from devices.ui import UiDevice, APP_PACKAGE
from devices.adb import AdbDevice

EMU_SERIAL = "emulator-5554"

def main():
    emu = UiDevice(EMU_SERIAL)

    # Clear app data
    logger.info("Clearing app data...")
    AdbDevice(EMU_SERIAL).shell(f"pm clear {APP_PACKAGE}")
    time.sleep(2)

    # Run ensure_drive_configured step by step
    logger.info("=== Step 1: open_app ===")
    emu.open_app()
    logger.info("Current: %s", emu.d.app_current())

    logger.info("=== Step 2: open_settings ===")
    emu.open_settings()
    logger.info("Current: %s", emu.d.app_current())

    logger.info("=== Step 3: click Sync ===")
    emu.d(text="Sync").click()
    time.sleep(2)
    logger.info("Current: %s", emu.d.app_current())

    logger.info("=== Step 4: check Select Folder ===")
    connect_btn = emu.d(text="Select Folder")
    logger.info("Select Folder: exists=%s", connect_btn.exists)

    if connect_btn.exists:
        logger.info("=== Step 5: click Select Folder ===")
        connect_btn.click()
        time.sleep(3)
        logger.info("Current: %s", emu.d.app_current())

        logger.info("=== Step 6: navigate SAF folder ===")
        deadline = time.time() + 60
        roots_opened = False
        while time.time() < deadline:
            if emu.d(text="PicPocketTest").exists:
                logger.info("Found PicPocketTest! Clicking...")
                emu.d(text="PicPocketTest").click()
                time.sleep(2)
                break
            if roots_opened:
                nc = emu.d(text="Nextcloud")
                if nc.exists:
                    logger.info("Found Nextcloud in roots, clicking...")
                    nc.click()
                    time.sleep(3)
                    continue
            sr = emu.d(description="Show roots")
            if sr.exists:
                logger.info("Clicking Show roots...")
                sr.click()
                roots_opened = True
                time.sleep(2)
                nc = emu.d(text="Nextcloud")
                if nc.exists:
                    logger.info("Found Nextcloud after roots, clicking...")
                    nc.click()
                    time.sleep(3)
                    continue
            time.sleep(2)
        else:
            logger.error("Timed out in SAF navigation")

        logger.info("=== Step 7: confirm folder ===")
        for text in ["USE THIS FOLDER", "SELECT", "Allow", "Select folder"]:
            btn = emu.d(text=text)
            if btn.wait(timeout=3.0):
                logger.info("Found confirm button '%s', clicking...", text)
                btn.click()
                time.sleep(3)
                logger.info("Current: %s", emu.d.app_current())
                break
        else:
            logger.error("No confirm button found!")

        logger.info("=== Step 8: ALLOW permission ===")
        for text in ["ALLOW", "Allow"]:
            btn = emu.d(text=text)
            if btn.wait(timeout=5.0):
                logger.info("Found '%s' button, clicking...", text)
                btn.click()
                time.sleep(2)
                logger.info("Current: %s", emu.d.app_current())
                break

        logger.info("=== Step 9: toggle sync ===")
        toggle = emu.d(description="Toggle sync")
        if toggle.wait(timeout=5.0):
            logger.info("Toggle exists! bounds=%s", toggle.info.get("bounds"))
            b = toggle.info.get("bounds", {})
            tx = (b["left"] + b["right"]) // 2
            ty = (b["top"] + b["bottom"]) // 2
            logger.info("Tapping toggle at (%d, %d)...", tx, ty)
            emu.adb.shell(f"input tap {tx} {ty}")
            time.sleep(2)

        logger.info("=== Step 10: check sync screen ===")
        sync_now = emu.d(text="Sync Now")
        logger.info("Sync Now: exists=%s, enabled=%s, clickable=%s",
                    sync_now.exists,
                    sync_now.info.get("enabled") if sync_now.exists else "N/A",
                    sync_now.info.get("clickable") if sync_now.exists else "N/A")

    else:
        logger.warning("No Select Folder button found - drive already configured?")

    logger.info("=== DONE ===")

if __name__ == "__main__":
    main()
