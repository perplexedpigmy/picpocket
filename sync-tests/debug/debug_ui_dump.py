#!/usr/bin/env python3
"""Dump the current sync screen after configuration."""

import logging
import sys
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

sys.path.insert(0, str(Path(__file__).parent.parent))
from devices.ui import UiDevice, APP_PACKAGE

EMU_SERIAL = "emulator-5554"
emu = UiDevice(EMU_SERIAL)

# Check current app
current = emu.d.app_current()
print(f"Current app: {current}")

# Dump UI
xml = emu.d.dump_hierarchy()
outfile = "/home/zun/dev/oc/pdfscanner/sync-tests/debug/ui_sync_configured.xml"
with open(outfile, "w") as f:
    f.write(xml)
print(f"UI dump saved to {outfile}")

# Find clickable elements
import re
lines = xml.splitlines()
for i, line in enumerate(lines):
    if 'clickable="true"' in line and 'com.picpocket.app' in line:
        print(f"  [{i}] {line.strip()}")

print("\n--- Lines around 'Sync Now' ---")
for i, line in enumerate(lines):
    if 'Sync Now' in line:
        start = max(0, i-5)
        end = min(len(lines), i+6)
        for j in range(start, end):
            print(f"  [{j}] {lines[j].strip()}")
