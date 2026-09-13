#!/usr/bin/env python3
"""Primitive-level probe: WebDAV client -> Nextcloud server -> rclone mount -> Google Drive.

Measures, for each write primitive, what the Nextcloud WebDAV side (Channel A)
and the Google Drive side via `rclone lsjson --hash` (Channel B) actually hold,
as a timestamped timeline. No PicPocket app, no SAF, no emulator.

Primitives:
  P1  fresh create (PUT new file)
  P2a in-place overwrite, same-length content
  P2b in-place overwrite, different-length content
  P3  delete existing file
  P4  same-path delete + immediate recreate
  P5  MOVE a settled file over an existing name (rename-overwrite)
  P6  read-back (GET) timing after each write (logged within each primitive)

Run:  python3 _debug_primitives.py [--only P1,P2a,P2b,P3,P4,P5] [--duration 120]
Logs go to sync-tests/tmp/primitives-<ts>.log; per-primitive JSON to
sync-tests/tmp/primitives-<ts>/.
"""

import argparse
import hashlib
import json
import logging
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import requests

from infra import nextcloud as nc

log = logging.getLogger("primitives")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s.%(msecs)03d [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)

BASE = "http://localhost:8080"
WEBDAV_BASE = f"{BASE}/remote.php/dav/files/testuser"
AUTH = ("testuser", "testpass123")
CTYPE = "application/octet-stream"

OUT_DIR = Path(__file__).parent / "tmp"


def md5(b: bytes) -> str:
    return hashlib.md5(b).hexdigest()


def _wvurl(path: str) -> str:
    return f"{WEBDAV_BASE}/{path.lstrip('/')}"


def webdav_put(path: str, data: bytes) -> int:
    r = requests.put(_wvurl(path), data=data, auth=AUTH,
                     headers={"Content-Type": CTYPE}, timeout=30)
    return r.status_code


def webdav_delete(path: str) -> int:
    r = requests.delete(_wvurl(path), auth=AUTH, timeout=30)
    return r.status_code


def webdav_move(src: str, dst: str, overwrite: str = "T") -> int:
    r = requests.request("MOVE", _wvurl(src), auth=AUTH, timeout=30,
                         headers={"Destination": _wvurl(dst), "Overwrite": overwrite})
    return r.status_code


def webdav_mkcol(path: str) -> int:
    r = requests.request("MKCOL", _wvurl(path), auth=AUTH, timeout=30)
    return r.status_code


def webdav_get(path: str):
    r = requests.get(_wvurl(path), auth=AUTH, timeout=30)
    return r.status_code, r.content


def webdav_propfind_depth1(folder: str) -> dict:
    """Depth:1 PROPFIND on a folder -> {name: (size, mime, etag)}."""
    r = requests.request("PROPFIND", _wvurl(folder), auth=AUTH,
                         headers={"Depth": "1"}, timeout=30)
    if r.status_code != 207:
        return {}
    parent_href = f"/remote.php/dav/files/testuser/{folder.lstrip('/')}"
    root = ET.fromstring(r.content)
    ns = {"d": "DAV:"}
    out = {}
    for resp in root.findall(".//d:response", ns):
        href = resp.find("d:href", ns)
        if href is None or href.text is None:
            continue
        href_text = href.text.rstrip("/")
        if href_text == parent_href.rstrip("/"):
            continue
        name = href_text.split("/")[-1]
        if not name:
            continue
        prop = resp.find("d:propstat/d:prop", ns)
        if prop is None:
            continue
        size_el = prop.find("d:getcontentlength", ns)
        mime_el = prop.find("d:getcontenttype", ns)
        etag_el = prop.find("d:getetag", ns)
        size = int(size_el.text) if size_el is not None and size_el.text else None
        mime = mime_el.text if mime_el is not None else None
        etag = etag_el.text if etag_el is not None else None
        out[name] = (size, mime, etag)
    return out


def drive_probe(folder: str) -> dict:
    """rclone lsjson on the whole PicPocketTest, filtered to the probe folder.

    The probe folder only exists on Drive after the WebDAV write propagates
    through Nextcloud -> rclone mount -> Drive, so lsjson must target the
    always-existing parent and filter; querying the subfolder directly errors
    with "directory not found" until propagation.
    """
    target = f"{nc.RCLONE_REMOTE}:{nc.RCLONE_REMOTE_PATH}"
    res = subprocess.run(
        ["rclone", "lsjson", "--recursive", "--hash", target],
        capture_output=True, text=True, timeout=30,
    )
    if res.returncode != 0:
        log.warning("rclone lsjson %s rc=%d: %s", folder, res.returncode,
                    (res.stderr or "").strip())
        return {}
    try:
        rows = json.loads(res.stdout)
    except json.JSONDecodeError:
        return {}
    prefix = folder.rstrip("/") + "/"
    out = {}
    for r in rows:
        if r.get("IsDir"):
            continue
        path = r.get("Path")
        if path == folder or path.startswith(prefix):
            out[path.split("/", 1)[-1]] = (r.get("Size"), r.get("MimeType"),
                                           (r.get("Hashes") or {}).get("md5"))
    return out


def sample(folder: str):
    return webdav_propfind_depth1(f"PicPocketTest/{folder}"), drive_probe(folder)


def wait_settled(folder: str, settle, timeout: float = 120.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        a, b = sample(folder)
        if settle(a, b):
            return a, b
        time.sleep(0.5)
    raise TimeoutError(f"folder {folder} did not settle within {timeout}s (a={a}, b={b})")


def b_full(b: dict) -> bool:
    """All tracked B rows are non-zero, md5-bearing, non-partial."""
    if not b:
        return False
    return all(
        size not in (None, 0)
        and mime != "application/x-partial-download"
        and md5hash
        for (size, mime, md5hash) in b.values()
    )


def b_has(b: dict, name: str, expected_md5: str) -> bool:
    row = b.get(name)
    if not row:
        return False
    size, mime, h = row
    return size not in (None, 0) and h == expected_md5 and mime != "application/x-partial-download"


def a_has(a: dict, name: str, expected_size: int) -> bool:
    row = a.get(name)
    if not row:
        return False
    return row[0] == expected_size


def a_missing(a: dict, name: str) -> bool:
    return name not in a


class Timeline:
    def __init__(self, folder: str):
        self.folder = folder
        self.rows = []

    def add(self, a: dict, b: dict):
        self.rows.append((time.time(), a, b))

    def write_json(self, path: Path):
        path.write_text(json.dumps(self.rows, indent=2, default=str))

    def print_table(self, names, t0):
        print(f"  {names}")
        print(f"  {'t+':>7} {'A size/mime/etag':>40}  {'B size/mime/md5':>48}")
        prev = None
        for t, a, b in self.rows:
            dt = t - t0
            a_str = " ".join(
                f"{n}={a[n][0]}" for n in names if n in a and a[n][0] is not None
            ) or "-"
            b_str = " ".join(
                f"{n}={sz}{('*' if h else '?')}{('/PARTIAL' if m == 'application/x-partial-download' else '')}"
                for n in names if n in b and (sz := b[n][0], m := b[n][1], h := b[n][2]) is not None and sz is not None
            ) or "-"
            key = (a_str, b_str)
            if key == prev:
                continue
            prev = key
            print(f"  +{dt:6.1f}s   A[{a_str}]   B[{b_str}]")


def run_primitive(name: str, setup, duration: float, max_after_settle: float = 8.0):
    print(f"\n========== {name} ==========")
    folder, names, t0, settle = setup()
    t = Timeline(folder)
    deadline = time.time() + duration
    settled_since = None
    while time.time() < deadline:
        a, b = sample(folder)
        t.add(a, b)
        if settle(a, b):
            if settled_since is None:
                settled_since = time.time()
            elif time.time() - settled_since >= max_after_settle:
                break
        else:
            settled_since = None
        time.sleep(0.5)
    t.print_table(names, t0)
    out = OUT_DIR / f"primitives-{run_ts}/{name}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    t.write_json(out)
    print(f"  -> {name}: rows={len(t.rows)} json={out}")
    return t


def C1() -> bytes:
    return bytes([i % 251 for i in range(131)])


def C2() -> bytes:
    return bytes([(i * 7 + 3) % 251 for i in range(131)])


def C3() -> bytes:
    return bytes([(i * 13 + 1) % 251 for i in range(512)])


def fresh_folder(name: str) -> str:
    nc.purge_remote_dir(name)
    webdav_mkcol(f"PicPocketTest/{name}")
    return name


def baseline_settle(folder: str, content: bytes, file_name: str = "f.bin"):
    webdav_put(f"PicPocketTest/{folder}/{file_name}", content)
    wait_settled(folder, lambda a, b: b_has(b, file_name, md5(content)))
    print(f"  baseline {file_name} settled on Drive ({len(content)}B)")


def p1_setup():
    f = fresh_folder("probe-p1")
    t0 = time.time()
    st = webdav_put(f"PicPocketTest/{f}/f.bin", C1())
    print(f"  PUT f.bin (131B) -> HTTP {st}  t0")
    settle = lambda a, b: b_has(b, "f.bin", md5(C1()))
    return f, ["f.bin"], t0, settle


def p2_setup_same():
    f = fresh_folder("probe-p2a")
    baseline_settle(f, C1())
    t0 = time.time()
    st = webdav_put(f"PicPocketTest/{f}/f.bin", C2())
    print(f"  same-length overwrite: PUT f.bin=C2 (131B) -> HTTP {st}  t0")
    settle = lambda a, b: b_has(b, "f.bin", md5(C2()))
    return f, ["f.bin"], t0, settle


def p2_setup_diff():
    f = fresh_folder("probe-p2b")
    baseline_settle(f, C1())
    t0 = time.time()
    st = webdav_put(f"PicPocketTest/{f}/f.bin", C3())
    print(f"  different-length overwrite: PUT f.bin=C3 (512B) -> HTTP {st}  t0")
    settle = lambda a, b: b_has(b, "f.bin", md5(C3()))
    return f, ["f.bin"], t0, settle


def p3_setup():
    f = fresh_folder("probe-p3")
    baseline_settle(f, C1())
    print("  baseline f.bin settled on Drive")
    t0 = time.time()
    st = webdav_delete(f"PicPocketTest/{f}/f.bin")
    print(f"  DELETE f.bin -> HTTP {st}  t0")
    settle = lambda a, b: a_missing(a, "f.bin") and "f.bin" not in b
    return f, ["f.bin"], t0, settle


def p4_setup():
    f = fresh_folder("probe-p4")
    baseline_settle(f, C1())
    print("  baseline f.bin settled on Drive")
    t0 = time.time()
    d = webdav_delete(f"PicPocketTest/{f}/f.bin")
    p = webdav_put(f"PicPocketTest/{f}/f.bin", C2())
    print(f"  DELETE f.bin -> HTTP {d}; PUT f.bin=C2 -> HTTP {p}  t0")
    settle = lambda a, b: b_has(b, "f.bin", md5(C2()))
    return f, ["f.bin"], t0, settle


def p5_setup():
    f = fresh_folder("probe-p5")
    webdav_put(f"PicPocketTest/{f}/g.bin", C1())
    webdav_put(f"PicPocketTest/{f}/f.bin", C2())
    wait_settled(
        f,
        lambda a, b: b_has(b, "g.bin", md5(C1())) and b_has(b, "f.bin", md5(C2())),
    )
    print("  baseline g.bin=C1 f.bin=C2 settled on Drive")
    t0 = time.time()
    st = webdav_move(f"PicPocketTest/{f}/g.bin", f"PicPocketTest/{f}/f.bin")
    print(f"  MOVE g.bin -> f.bin (overwrite) -> HTTP {st}  t0")
    settle = lambda a, b: b_has(b, "f.bin", md5(C1())) and "g.bin" not in b
    return f, ["f.bin", "g.bin"], t0, settle


def readback(folder: str, name: str, expected: bytes, t0: float):
    time.sleep(0.05)
    st, content = webdav_get(f"PicPocketTest/{folder}/{name}")
    dt = time.time() - t0
    ok = content == expected
    print(f"  [readback] +{dt:.2f}s GET {name} HTTP {st} len={len(content)} "
          f"md5={md5(content)[:8]} match={ok}")
    return ok


def main():
    sys.stdout.reconfigure(line_buffering=True)
    global run_ts
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", default="P1,P2a,P2b,P3,P4,P5")
    parser.add_argument("--duration", type=float, default=120.0)
    args = parser.parse_args()

    run_ts = time.strftime("%Y%m%d-%H%M%S")
    logfile = OUT_DIR / f"primitives-{run_ts}.log"
    fh = logging.FileHandler(logfile)
    fh.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    logging.getLogger().addHandler(fh)

    log.info("Ensuring Nextcloud stack + rclone mount are up")
    nc.start()
    nc.reset_bruteforce()

    log.info("Cleaning PicPocketTest fully (purge_drive + trash)")
    nc.purge_drive(timeout=120)
    nc.empty_drive_trash()
    time.sleep(2)

    only = {p.strip() for p in args.only.split(",")}

    if "P1" in only:
        run_primitive("P1-fresh-create", p1_setup, args.duration)
        # readback performed inside primitive via settle? do explicit after:
        readback("probe-p1", "f.bin", C1(), time.time())

    if "P2a" in only:
        run_primitive("P2a-overwrite-same-len", p2_setup_same, args.duration)
        readback("probe-p2a", "f.bin", C2(), time.time())

    if "P2b" in only:
        run_primitive("P2b-overwrite-diff-len", p2_setup_diff, args.duration)
        readback("probe-p2b", "f.bin", C3(), time.time())

    if "P3" in only:
        run_primitive("P3-delete", p3_setup, args.duration)

    if "P4" in only:
        run_primitive("P4-delete-recreate", p4_setup, args.duration)
        readback("probe-p4", "f.bin", C2(), time.time())

    if "P5" in only:
        run_primitive("P5-move-overwrite", p5_setup, args.duration)
        readback("probe-p5", "f.bin", C1(), time.time())

    log.info("Cleanup: purging probe folders from Drive")
    for pf in ("probe-p1", "probe-p2a", "probe-p2b", "probe-p3", "probe-p4", "probe-p5"):
        nc.purge_remote_dir(pf)
    nc.empty_drive_trash()

    print(f"\n===== done. log={logfile} json dir={OUT_DIR}/primitives-{run_ts}/ =====")


if __name__ == "__main__":
    main()
