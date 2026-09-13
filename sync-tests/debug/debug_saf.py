#!/usr/bin/env python3
"""Debug SAF folder navigation for Drive configuration."""

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

EMU_SERIAL = "emulator-5554"

def dump_ui(emu, label=""):
    outfile = f"/home/zun/dev/oc/pdfscanner/sync-tests/debug/ui_{label}.xml"
    xml = emu.d.dump_hierarchy()
    with open(outfile, "w") as f:
        f.write(xml)
    logger.info("UI dump saved to %s", outfile)
    return outfile

def assert_text(emu, text):
    el = emu.d(text=text)
    logger.info("  '%s': exists=%s, bounds=%s",
                text, el.exists, el.info.get("bounds") if el.exists else "N/A")
    return el.exists

def main():
    emu = UiDevice(EMU_SERIAL)

    # Clear app data
    AdbDevice(EMU_SERIAL).shell(f"pm clear {APP_PACKAGE}")
    time.sleep(2)

    # Open app and go to Sync screen
    emu.open_app()
    dump_ui(emu, "1_home")
    emu.open_settings()
    dump_ui(emu, "2_settings")

    emu.d(text="Sync").click()
    time.sleep(2)
    dump_ui(emu, "3_sync_screen")

    # Check for Select Folder button
    connect_btn = emu.d(text="Select Folder")
    if connect_btn.wait(timeout=5.0):
        logger.info("Clicking Select Folder...")
        connect_btn.click()
        time.sleep(3)

        # Check current app - should be SAF picker
        current = emu.d.app_current()
        logger.info("Current app after Select Folder: %s", current)

        dump_ui(emu, "4_saf_picker")

        # Look for key SAF elements
        logger.info("SAF elements:")
        for text in ["Show roots", "Nextcloud", "PicPocketTest", "Downloads",
                     "Recent", "com.nextcloud.client", "USE THIS FOLDER",
                     "ALLOW", "Allow"]:
            assert_text(emu, text)

        # Check for description-based elements
        show_roots = emu.d(description="Show roots")
        logger.info("  Show roots (desc): exists=%s, bounds=%s",
                    show_roots.exists,
                    show_roots.info.get("bounds") if show_roots.exists else "N/A")

        # Try clicking Show roots
        if show_roots.exists:
            logger.info("Clicking Show roots...")
            show_roots.click()
            time.sleep(3)
            dump_ui(emu, "5_after_roots")

            # Check for Nextcloud
            nextcloud_el = emu.d(text="Nextcloud")
            logger.info("  Nextcloud: exists=%s", nextcloud_el.exists)
            if nextcloud_el.exists:
                nextcloud_el.click()
                time.sleep(3)
                dump_ui(emu, "6_nextcloud_browser")

                # Check for PicPocketTest
                ppt = emu.d(text="PicPocketTest")
                logger.info("  PicPocketTest: exists=%s, bounds=%s",
                            ppt.exists, ppt.info.get("bounds") if ppt.exists else "N/A")
            else:
                logger.info("Nextcloud not found in roots. Dumping all text elements...")
                xml = emu.d.dump_hierarchy()
                import re
                texts = set()
                for match in re.finditer(r'text="([^"]*)"', xml):
                    t = match.group(1)
                    if t and len(t) < 100:
                        texts.add(t)
                for t in sorted(texts):
                    logger.info("  text='%s'", t)

    else:
        logger.warning("Select Folder button not found!")

    logger.info("Debug complete")

if __name__ == "__main__":
    main()
