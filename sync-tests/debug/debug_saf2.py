#!/usr/bin/env python3
"""Debug SAF - check what's shown after clicking Nextcloud in folder picker."""

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

def dump_ui(emu, label):
    outfile = f"/home/zun/dev/oc/pdfscanner/sync-tests/debug/ui_{label}.xml"
    xml = emu.d.dump_hierarchy()
    with open(outfile, "w") as f:
        f.write(xml)
    logger.info("UI dump saved to %s", outfile)
    return xml

def main():
    emu = UiDevice(EMU_SERIAL)

    # Check current app
    current = emu.d.app_current()
    logger.info("Current app: %s", current)

    # If we're at SAF picker, explore it
    if "documentsui" in current.get("package", ""):
        logger.info("=== In SAF picker ===")
        dump_ui(emu, "saf_current")

        # Check key elements
        import re
        xml = emu.d.dump_hierarchy()
        texts = set()
        for match in re.finditer(r'text="([^"]*)"', xml):
            t = match.group(1)
            if t and len(t) < 100:
                texts.add(t)
        logger.info("All visible text elements:")
        for t in sorted(texts):
            logger.info("  '%s'", t)

        # Check clickable items
        nodes = re.findall(r'<node[^>]*clickable="true"[^>]*text="([^"]*)"', xml)
        logger.info("Clickable items with text:")
        for n in nodes:
            logger.info("  clickable: '%s'", n)

    else:
        logger.warning("Not in SAF picker. Current: %s", current)

if __name__ == "__main__":
    main()
