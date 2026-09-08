#!/usr/bin/env python3
"""Probe: does the Nextcloud client's SAF createDocument give us an atomic lock?

Decides the mutex redesign. If creating an EXISTING name fails (null) rather
than returning the existing doc or making a "(1)" conflict copy, directory
creation (MKCOL) can be an exclusive "lock it once" primitive.

Scenarios, all against names that already exist on the server:

  S1 dir  probeLock  created by device A via SAF (fresh, name known to B's DB
                    only if B already synced it)
  S2 file probeLockF created by device A via SAF (A writes marker 'A1'/'A2')
  S3 dir  probeLockY created OUT-OF-BAND via curl MKCOL (guaranteed invisible
                    to both clients' DBs — the stale-view case)
  S4 file probeLockG created OUT-OF-BAND via curl PUT (marker 'Y')

For each, device B's createDocument result is classified:
  - NULL            -> exclusive create works  (the desired outcome)
  - returned existing doc -> not exclusive (overwrite path)
  - "(1)" copy      -> not exclusive (conflict copy)

Run: python3 _probe_lock_exclusive.py
"""

import logging
import subprocess
import sys
import time
from pathlib import Path

from _debug_primitives import (
    webdav_delete,
    webdav_get,
    webdav_mkcol,
    webdav_propfind_depth1,
    webdav_put,
)

log = logging.getLogger("lock_probe")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s.%(msecs)03d [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)

TEST_CLASS = "com.picpocket.app.drive.SAFWriteProbeTest"
RUNNER = "com.picpocket.app.test/androidx.test.runner.AndroidJUnitRunner"

A_SERIAL = "emulator-5554"
B_SERIAL = "emulator-5556"

DIR_MIME = "httpd/unix-directory"
LOCK_NAMES = ["probeLock", "probeLockF", "probeLockY", "probeLockG"]


def adb(serial: str, *args: str):
    return subprocess.run(
        ["adb", "-s", serial, *args], capture_output=True, text=True, timeout=180,
    )


def run_instrument(serial: str, method: str):
    log.info("--- am instrument %s#%s on %s ---", TEST_CLASS, method, serial)
    proc = subprocess.run(
        ["adb", "-s", serial, "shell", "am", "instrument", "-w",
         "-e", "class", f"{TEST_CLASS}#{method}", RUNNER],
        capture_output=True, text=True, timeout=240,
    )
    for line in (proc.stdout or "").splitlines():
        if "OK (" in line or "FAILURES" in line or "INSTRUMENTATION" in line:
            log.info("instrument: %s", line.strip())
    if proc.returncode != 0:
        log.warning("instrument rc=%d: %s", proc.returncode, (proc.stderr or "")[:300])
    time.sleep(1)


def root_listing() -> dict:
    return webdav_propfind_depth1("PicPocketTest")


def wait_for_root_names(names: list[str], timeout: float = 60.0) -> dict:
    t0 = time.time()
    last = {}
    while time.time() - t0 < timeout:
        last = root_listing()
        if all(n in last for n in names):
            return last
        time.sleep(1)
    log.warning("timeout waiting for server names %s; have %s", names, sorted(last))
    return last


def fmt_row(row):
    if row is None:
        return "absent"
    size, mime, etag = row
    return f"size={size} mime={mime}"

def main() -> int:
    for name in LOCK_NAMES:
        webdav_delete(f"PicPocketTest/{name}")
    time.sleep(0.5)
    for serial in (A_SERIAL, B_SERIAL):
        adb(serial, "logcat", "-c")
    time.sleep(0.5)

    # --- Phase 1: device A creates dir + file twice (same-device case) ---
    run_instrument(A_SERIAL, "createDirTwiceSameDevice")
    run_instrument(A_SERIAL, "createFileTwiceSameDevice")
    wait_for_root_names(["probeLock", "probeLockF"])
    log.info("after A: %s", {k: fmt_row(v) for k, v in root_listing().items() if k in LOCK_NAMES})

    # --- Phase 2: out-of-band server-side creates (stale-DB case) ---
    code = webdav_mkcol("PicPocketTest/probeLockY")
    log.info("out-of-band MKCOL probeLockY -> %d", code)
    code = webdav_put("PicPocketTest/probeLockG", b"Y-marker")
    log.info("out-of-band PUT probeLockG -> %d", code)
    wait_for_root_names(["probeLockY", "probeLockG"])

    # --- Phase 3: device B attempts to create all four existing names ---
    run_instrument(B_SERIAL, "createExistingNamesCrossDevice")
    time.sleep(3)

    # --- Phase 4: server ground truth ---
    listing = root_listing()
    print("\n========== SERVER GROUND TRUTH ==========")
    for name in sorted(listing):
        if name.startswith("probe"):
            print(f"  {name}: {fmt_row(listing[name])}")
    absent = [n for n in LOCK_NAMES if n not in listing]
    print(f"  (absent: {absent or 'none'})")

    for f in ("probeLockF", "probeLockG"):
        st, content = webdav_get(f"PicPocketTest/{f}")
        print(f"  GET {f}: HTTP {st} content={content!r}")

    conflict_copies = [
        n for n in listing if any(base in n for base in LOCK_NAMES)
        and n not in LOCK_NAMES
    ]
    print(f"\n  conflict copies ((1) style): {conflict_copies or 'none'}")

    print("\n========== DEVICE LOGCAT (LOCK lines) ==========")
    for serial in (A_SERIAL, B_SERIAL):
        out = adb(serial, "logcat", "-d").stdout or ""
        lines = [l for l in out.splitlines() if "LOCK:" in l or "SAFProbe" in l]
        print(f"--- {serial} ---")
        for l in lines:
            print("  " + l)

    print("\n========== VERDICT ==========")
    print("Read device B's create results above. Exclusive-create works if B's")
    print("createDocument returned NULL (or threw) for existing names and no")
    print("'(1)' copies appeared on the server.")
    return 0


if __name__ == "__main__":
    sys.exit(main())