"""Debug: step through sync flow and check logcat after each step."""

import logging
import time

from devices.adb import AdbDevice
from devices.ui import UiDevice, APP_PACKAGE

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)

def dump_logcat(adb, label):
    """Dump app-specific logcat lines."""
    log = adb.logcat()
    lines = [l for l in log.split("\n") if "com.picpocket.app" in l or "PicPocket" in l]
    print(f"\n=== Logcat: {label} ({len(lines)} lines) ===")
    for line in lines[-30:]:
        print(f"  {line.strip()}")
    print()

def main():
    serial = AdbDevice.discover_devices()[0]
    ui = UiDevice(serial)
    adb = AdbDevice(serial)

    # Fresh start
    adb.shell(f"pm clear {APP_PACKAGE}")
    time.sleep(2)
    if not adb.is_installed():
        adb.install_apk("/home/zun/dev/oc/pdfscanner/app/build/outputs/apk/debug/app-debug.apk")

    # Step 1: configure drive
    print("\n=== Configuring drive ===")
    ui.open_app()
    ui.open_settings()
    ui.d(text="Sync").click()
    time.sleep(1)
    connect_btn = ui.d(text="Select Folder")
    connect_btn.wait(timeout=5.0)
    connect_btn.click()
    time.sleep(2)
    ui._navigate_saf_folder("PicPocketTest", 30.0)
    time.sleep(2)

    # Allow dialog
    allow_btn = ui.d(text="ALLOW")
    if allow_btn.wait(timeout=5.0):
        print("ALLOW button found, clicking...")
        b = allow_btn.info.get("bounds", {})
        adb.shell(f"input tap {(b['left']+b['right'])//2} {(b['top']+b['bottom'])//2}")
        time.sleep(3)

    # Toggle sync
    toggle_elem = ui.d(description="Toggle sync")
    if toggle_elem.wait(timeout=5.0):
        b = toggle_elem.info.get("bounds", {})
        print(f"Toggle bounds: {b}")
        adb.shell(f"input tap {(b['left']+b['right'])//2} {(b['top']+b['bottom'])//2}")
        time.sleep(2)
        print("Toggle clicked")

    # Dump sync screen
    xml = ui.d.dump_hierarchy()
    for line in xml.split("\n"):
        if 'picpocket' in line.lower() and ('text="' in line or 'content-desc="' in line):
            t = line.split('text="')[1].split('"')[0] if 'text="' in line else ""
            d = line.split('content-desc="')[1].split('"')[0] if 'content-desc="' in line else ""
            clickable = '"clickable="true"' in line
            if t or d:
                print(f"  t='{t}' d='{d}' click={clickable}")

    # Click Sync Now
    sync_text = ui.d(text="Sync Now")
    if sync_text.wait(timeout=5.0):
        b = sync_text.info.get("bounds", {})
        print(f"\nSync Now bounds: {b}")
        # Try clicking via ADB
        print("Clicking Sync Now via ADB tap...")
        adb.shell(f"input tap {(b['left']+b['right'])//2} {(b['top']+b['bottom'])//2}")
        time.sleep(5)
        dump_logcat(adb, "After Sync Now tap")

        # Also try clicking the button area above the text
        print("Clicking slightly above (button center)...")
        adb.shell(f"input tap {(b['left']+b['right'])//2} {b['top'] - 30}")
        time.sleep(5)
        dump_logcat(adb, "After button area tap")

    # Check all clickable elements in Sync screen
    print("\n=== All clickable elements on Sync screen ===")
    all_xml = ui.d.dump_hierarchy()
    for line in all_xml.split("\n"):
        if 'clickable="true"' in line and 'picpocket' in line.lower():
            d = line.split('content-desc="')[1].split('"')[0] if 'content-desc="' in line else ""
            t = line.split('text="')[1].split('"')[0] if 'text="' in line else ""
            clz = line.split('class="')[1].split('"')[0] if 'class="' in line else ""
            print(f"  t='{t}' d='{d}' class={clz}")

if __name__ == "__main__":
    main()
