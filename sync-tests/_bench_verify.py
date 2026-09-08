#!/usr/bin/env python3
"""Benchmark of size/arrival signals after a WebDAV write.

For each iteration the script creates a file (0-byte PUT, mirroring the app's
createDocument) then writes real content (PUT), and samples four signals until
they all report the final size:

  A_propfind  Depth:1 PROPFIND getcontentlength  (current oracle method)
  A_head      HTTP HEAD Content-Length + status  (candidate fast method)
  A_get       HTTP GET body length               (heavy but authoritative)
  B_drive     rclone lsjson --recursive --hash   (Drive API: Size + md5)

WebDAV signals are sampled every ~250ms; the Drive side every ~2s (rclone's
API round-trip floor). Reports, per signal and per iteration, the latency from
the content write until the signal first reports the full size (and, for
Drive, until md5 is present = fully uploaded), plus any transient 0/404/None
readings. The "Drive placeholder window" measures how long Drive shows the file
as 0-byte/md5-less before finalizing.

Usage:  python3 _bench_verify.py [--iterations 5] [--size 4096] [--timeout 120]
Logs:   sync-tests/tmp/bench-verify-<ts>.json  (+ console summary)
"""

import argparse
import hashlib
import json
import logging
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import requests

from infra import nextcloud as nc

log = logging.getLogger("bench_verify")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s.%(msecs)03d [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)

BASE = "http://localhost:8080"
WB = f"{BASE}/remote.php/dav/files/testuser"
AUTH = ("testuser", "testpass123")

OUT_DIR = Path(__file__).parent / "tmp"
FOLDER = "bench-vfy"
FILE = "f.bin"


def wvurl(path: str) -> str:
    return f"{WB}/{path}"


def content(size: int) -> bytes:
    return bytes((i * 17 + 5) % 256 for i in range(size))


def put(path: str, data: bytes) -> int:
    r = requests.put(wvurl(path), data=data, auth=AUTH,
                     headers={"Content-Type": "application/octet-stream"}, timeout=30)
    return r.status_code


def propfind_size(path: str):
    """Depth:1 PROPFIND on parent -> getcontentlength of path, or None."""
    parent = path.rsplit("/", 1)[0]
    r = requests.request("PROPFIND", wvurl(parent), auth=AUTH,
                         headers={"Depth": "1"}, timeout=30)
    if r.status_code != 207:
        return None
    want = "/remote.php/dav/files/testuser/" + path
    root = ET.fromstring(r.content)
    ns = {"d": "DAV:"}
    for resp in root.findall(".//d:response", ns):
        href = resp.find("d:href", ns)
        if href is None or href.text is None:
            continue
        if href.text.rstrip("/") != want.rstrip("/"):
            continue
        el = resp.find("d:propstat/d:prop/d:getcontentlength", ns)
        return int(el.text) if el is not None and el.text else None
    return None


def head_info(path: str):
    """(status, Content-Length or None)."""
    r = requests.head(wvurl(path), auth=AUTH, timeout=30)
    cl = r.headers.get("Content-Length")
    return r.status_code, (int(cl) if cl and cl.isdigit() else None)


def get_len(path: str):
    r = requests.get(wvurl(path), auth=AUTH, timeout=30)
    if r.status_code != 200:
        return None
    return len(r.content)


def drive_rows(folder: str) -> dict:
    """{name: (Size, md5)} under the bench folder, from the Drive API."""
    res = subprocess.run(
        ["rclone", "lsjson", "--recursive", "--hash",
         f"{nc.RCLONE_REMOTE}:{nc.RCLONE_REMOTE_PATH}"],
        capture_output=True, text=True, timeout=60,
    )
    if res.returncode != 0:
        log.warning("drive_rows rclone rc=%s stderr=%r", res.returncode, res.stderr[:300])
        return {}
    try:
        rows = json.loads(res.stdout)
    except json.JSONDecodeError:
        return {}
    prefix = f"{folder}/"
    out = {}
    for r in rows:
        if r.get("IsDir"):
            continue
        p = r.get("Path")
        if p.startswith(prefix):
            name = p.split("/", 1)[1]
            out[name] = (r.get("Size"), (r.get("Hashes") or {}).get("md5"))
    return out


def drive_full(row, expected_size, expected_md5) -> bool:
    if not row:
        return False
    return row[0] == expected_size and row[1] == expected_md5


def webdav_full(p, h, g, expected_size) -> bool:
    return p == expected_size and h[0] == 200 and h[1] == expected_size and g == expected_size


def sample_webdav(path, expected_size, rec):
    p = propfind_size(path)
    h = head_info(path)
    g = get_len(path)
    rec(dict(sig="propfind", size=p, full=(p == expected_size)))
    rec(dict(sig="head", status=h[0], size=h[1],
             full=(h[0] == 200 and h[1] == expected_size)))
    rec(dict(sig="get", size=g, full=(g == expected_size)))
    return p, h, g


def drive_sampler(stop, t0, expected_size, expected_md5, out, lock, drive_calls, folder):
    while not stop.is_set():
        t_start = time.time()
        rows = drive_rows(folder)
        drive_calls.append(time.time() - t_start)
        row = rows.get(FILE)
        seen = row is not None
        full = drive_full(row, expected_size, expected_md5)
        with lock:
            out.append(dict(
                sig="drive", seen=seen,
                size=row[0] if row else None,
                md5=(row[1] if row else None),
                full=full,
                dt=round(time.time() - t0, 2),
            ))
        stop.wait(2.0)


def run_iteration(idx, size, timeout, out_dir):
    expected = content(size)
    expected_md5 = md5(expected)
    # Unique folder per iteration: reusing a name that was purged from Drive
    # leaves a stale node in the mount's dir-cache, and every subsequent upload
    # under that name wedges (the "0-byte on Drive" symptom). A fresh name has
    # no stale state, so write-back flushes normally (~5-10s).
    folder = f"bench-{time.strftime('%H%M%S')}-{idx}"
    path = f"PicPocketTest/{folder}/{FILE}"

    nc.reset_bruteforce()
    mkcol_st = requests.request(
        "MKCOL", wvurl(f"PicPocketTest/{folder}"), auth=AUTH, timeout=30
    ).status_code

    t0 = time.time()
    create_st = put(path, b"")
    write_st = put(path, expected)
    t_write = time.time() - t0
    print(f"[iter {idx}] MKCOL={mkcol_st}  create PUT(0B)->HTTP {create_st}  content PUT({size}B)->HTTP {write_st}  "
          f"(write completed +{t_write:.2f}s)")

    out = []
    lock = threading.Lock()
    stop = threading.Event()
    drive_calls = []
    t = threading.Thread(target=drive_sampler,
                         args=(stop, t0, size, expected_md5, out, lock, drive_calls, folder),
                         daemon=True)
    t.start()

    first_full = {}
    drive_seen_dt = None
    webdav_done = False
    deadline = time.time() + timeout
    while time.time() < deadline:
        if not webdav_done:
            # Poll WebDAV signals only until each first reports full. Keep
            # polling after that blocks the Drive flush: GET reads keep the
            # file open on the mount, so rclone's vfs write-back never fires
            # while GETs are in flight.
            p, h, g = sample_webdav(path, size, lambda e: out.append(dict(e, dt=round(time.time() - t0, 2))))
            for sig, full in (("propfind", p == size),
                              ("head", h[0] == 200 and h[1] == size),
                              ("get", g == size)):
                if full and sig not in first_full:
                    first_full[sig] = round(time.time() - t0, 2)
            if all(s in first_full for s in ("propfind", "head", "get")):
                webdav_done = True
        with lock:
            drive = [e for e in out if e["sig"] == "drive"][-1] if any(e["sig"] == "drive" for e in out) else None
        if drive is not None:
            if drive["seen"] and drive_seen_dt is None:
                drive_seen_dt = drive["dt"]
            if drive["full"] and "drive" not in first_full:
                first_full["drive"] = drive["dt"]
        if all(s in first_full for s in ("propfind", "head", "get", "drive")):
            break
        time.sleep(0.25)

    stop.set()
    t.join(timeout=5)

    # Drive placeholder window: file visible on Drive but not yet finalized.
    drive_seen_full_dt = first_full.get("drive")
    placeholder_window = (drive_seen_full_dt - drive_seen_dt) if (drive_seen_dt is not None and drive_seen_full_dt) else None

    with lock:
        drive_events = [e for e in out if e["sig"] == "drive"]
    drive_zero = sum(1 for e in drive_events if e["seen"] and (e["size"] in (None, 0) or e["md5"] is None))
    head_odd = sum(1 for e in out if e["sig"] == "head" and (e["status"] != 200 or e["size"] is None))
    propfind_odd = sum(1 for e in out if e["sig"] == "propfind" and e["size"] is None)

    result = dict(
        iter=idx, size=size, t_write=round(t_write, 2),
        first_full=first_full,
        drive_seen_dt=drive_seen_dt,
        drive_placeholder_window_s=placeholder_window,
        drive_zero_samples=drive_zero,
        head_odd_samples=head_odd,
        propfind_missing_samples=propfind_odd,
        drive_poll_s=round(sum(drive_calls) / len(drive_calls), 2) if drive_calls else None,
        samples=len(out),
        timed_out=all(s in first_full for s in ("propfind", "head", "get", "drive")) is False,
    )

    # Clean this iteration's folder via the mount (never out-of-band rclone
    # purge, which would desync the mount's dir-cache for any reused name).
    # Fresh names have no stale state to worry about, but tidy up regardless.
    nc.purge_folder_via_mount(folder)
    return result, out


def wdel(path: str) -> int:
    r = requests.delete(wvurl(path), auth=AUTH, timeout=30)
    return r.status_code


def get_content(path: str):
    r = requests.get(wvurl(path), auth=AUTH, timeout=30)
    if r.status_code != 200:
        return None
    return r.content


def read_back_and_compare(path: str, expected: bytes):
    got = get_content(path)
    size = len(got) if got is not None else None
    return size, got == expected


def read_vfs_state(rel_path: str):
    base = nc.RCLONE_MOUNT_POINT.parent / "rclone-vfs-cache" / "vfsMeta"
    hits = list(base.glob(f"*/PicPocketTest/{rel_path}"))
    if not hits:
        return None
    try:
        d = json.loads(hits[0].read_text())
    except Exception:
        return None
    return (d.get("Dirty"), d.get("Fingerprint"))


def drive_sampler_all(stop, t0, out, lock, drive_calls, folder):
    while not stop.is_set():
        t_start = time.time()
        rows = drive_rows(folder)
        drive_calls.append(time.time() - t_start)
        with lock:
            out.append(dict(sig="drive", rows={k: v for k, v in rows.items()},
                            dt=round(time.time() - t0, 2)))
        stop.wait(2.0)


PATTERNS = ("control", "lock", "lock-heartbeat", "devices", "metadata", "two-writer")


def lock_payload(idx: int) -> bytes:
    return json.dumps({
        "lockedBy": f"dev-{idx}",
        "claimToken": f"tok-{idx}-{time.time():.0f}",
        "acquiredAt": int(time.time() * 1000),
        "heartbeat": int(time.time() * 1000),
    }).encode()


def run_pattern_iteration(pattern, idx, folder, path_base, timeout):
    expected_control = content(4096)
    ctrl_md5 = md5(expected_control)
    ctrl_name = "control.bin" if pattern == "control" else f"control-{idx}.bin"
    target_name = {"lock": "sync-lock.json", "lock-heartbeat": "sync-lock.json",
                   "devices": "devices.json", "metadata": "metadata.2.json",
                   "two-writer": "devices.json"}.get(pattern)
    target_data = None
    if pattern in ("lock", "lock-heartbeat"):
        target_data = lock_payload(idx)
    elif pattern == "devices":
        target_data = json.dumps({"device": f"dev-{idx}", "devices": [f"d{idx}-{i}" for i in range(30)]}).encode()
    elif pattern == "metadata":
        target_data = b'{"v":2}'
    target_path = f"{path_base}/{target_name}" if target_name else None
    ctrl_path = f"{path_base}/{ctrl_name}"

    t0 = time.time()
    out, lock = [], threading.Lock()
    stop = threading.Event()
    drive_calls = []
    t = threading.Thread(target=drive_sampler_all,
                         args=(stop, t0, out, lock, drive_calls, folder), daemon=True)
    t.start()

    readbacks = []

    if pattern == "control":
        put(ctrl_path, b"")
        put(ctrl_path, expected_control)
    elif pattern == "lock":
        if propfind_size(target_path) is not None:
            wdel(target_path)
        put(target_path, b"")
        put(target_path, target_data)
        s, ok = read_back_and_compare(target_path, target_data)
        readbacks.append(dict(size=s, ok=ok))
        put(ctrl_path, expected_control)
    elif pattern == "lock-heartbeat":
        if propfind_size(target_path) is not None:
            wdel(target_path)
        for cyc in range(4):
            put(target_path, b"")
            put(target_path, target_data)
            s, ok = read_back_and_compare(target_path, target_data)
            readbacks.append(dict(size=s, ok=ok))
            if cyc < 3:
                time.sleep(30)
        wdel(target_path)
        put(ctrl_path, expected_control)
    elif pattern == "devices":
        for cyc in range(3):
            if cyc == 0:
                put(target_path, b"")
            put(target_path, target_data)
            s, ok = read_back_and_compare(target_path, target_data)
            readbacks.append(dict(size=s, ok=ok))
            time.sleep(1)
        s, ok = read_back_and_compare(target_path, target_data)
        readbacks.append(dict(size=s, ok=ok))
        put(ctrl_path, expected_control)
    elif pattern == "metadata":
        put(f"{path_base}/metadata.1.json", b"")
        put(f"{path_base}/metadata.1.json", b'{"v":1}')
        wdel(f"{path_base}/metadata.1.json")
        put(target_path, b"")
        put(target_path, target_data)
        put(ctrl_path, expected_control)
    elif pattern == "two-writer":
        def writer(label, delay):
            if delay:
                time.sleep(delay)
            for cyc in range(3):
                put(target_path, b"")
                payload = json.dumps({"device": label, "cycle": cyc}).encode()
                put(target_path, payload)
                s, ok = read_back_and_compare(target_path, payload)
                readbacks.append(dict(who=label, cycle=cyc, size=s, ok=ok))
                time.sleep(0.3)
        ta = threading.Thread(target=writer, args=("A", 0))
        tb = threading.Thread(target=writer, args=("B", 1))
        ta.start(); tb.start(); ta.join(); tb.join()
        put(ctrl_path, expected_control)

    deadline = time.time() + timeout
    ctrl_full_dt = tgt_full_dt = tgt_seen_dt = None
    while time.time() < deadline:
        with lock:
            ev = [e for e in out if e["sig"] == "drive"][-1] if out else None
        if ev is not None:
            rows = ev["rows"]
            if ctrl_full_dt is None:
                r = rows.get(ctrl_name)
                if r and r[0] == 4096 and r[1] == ctrl_md5:
                    ctrl_full_dt = ev["dt"]
            if target_name and target_data is not None:
                if tgt_seen_dt is None and target_name in rows:
                    tgt_seen_dt = ev["dt"]
                r = rows.get(target_name)
                if tgt_full_dt is None and r and r[0] == len(target_data) and r[1] == md5(target_data):
                    tgt_full_dt = ev["dt"]
        if ctrl_full_dt is not None:
            break
        time.sleep(0.25)

    stop.set()
    t.join(timeout=5)

    with lock:
        events = [e for e in out if e["sig"] == "drive"]
    tgt_zero = sum(1 for e in events
                   if target_name and target_name in e["rows"]
                   and (e["rows"][target_name][0] in (None, 0) or e["rows"][target_name][1] is None))
    ph = (tgt_full_dt - tgt_seen_dt) if (target_name and target_data is not None
                                         and tgt_seen_dt is not None and tgt_full_dt is not None) else None

    vfs = {}
    if target_name:
        vfs["target"] = read_vfs_state(f"{folder}/{target_name}")
    vfs["control"] = read_vfs_state(f"{folder}/{ctrl_name}")

    return dict(
        pattern=pattern, iter=idx, readbacks=readbacks,
        target_seen_dt=tgt_seen_dt, target_full_dt=tgt_full_dt,
        target_placeholder_window_s=ph, target_zero_samples=tgt_zero,
        control_full_dt=ctrl_full_dt, wedged=(ctrl_full_dt is None),
        vfs=vfs, timed_out=(ctrl_full_dt is None),
        poll_s=round(sum(drive_calls) / len(drive_calls), 2) if drive_calls else None,
    ), out


def fmt_pattern_result(r: dict) -> str:
    parts = [f"seen={r['target_seen_dt']}s" if r["target_seen_dt"] is not None else "seen=never"]
    parts.append(f"full={r['target_full_dt']}s" if r["target_full_dt"] is not None else "full=never")
    parts.append(f"ph={r['target_placeholder_window_s']}s" if r["target_placeholder_window_s"] is not None else "ph=-")
    parts.append(f"zero={r['target_zero_samples']}")
    rb = r["readbacks"]
    if rb:
        sizes = [x.get("size") for x in rb]
        oks = sum(1 for x in rb if x.get("ok"))
        parts.append(f"readback={len(rb)}x sizes={sizes} ok={oks}/{len(rb)}")
    parts.append(f"control_full={r['control_full_dt']}s" if r["control_full_dt"] is not None else "control=WEDGED")
    for k in ("target", "control"):
        st = r["vfs"].get(k)
        if st:
            parts.append(f"{k}_vfs=Dirty={st[0]} fp={str(st[1])[:8]}")
    return "  ".join(parts)


def run_pattern_matrix(pattern, iterations, timeout, outdir):
    folder = f"patt-{pattern}-{time.strftime('%H%M%S')}"
    path_base = f"PicPocketTest/{folder}"
    nc.reset_bruteforce()
    requests.request("MKCOL", wvurl(path_base), auth=AUTH, timeout=30)
    print(f"\n===== pattern {pattern} (folder {folder}, n={iterations}) =====")
    results, timelines = [], {}
    for idx in range(1, iterations + 1):
        result, tl = run_pattern_iteration(pattern, idx, folder, path_base, timeout)
        results.append(result)
        timelines[idx] = dict(pattern=pattern, timeline=tl)
        print(f"    {pattern}[{idx}]: " + fmt_pattern_result(result))
    nc.purge_folder_via_mount(folder)
    return results, timelines


def fmt_ff(ff: dict) -> str:
    if not ff:
        return "(never full)"
    return "  ".join(f"{k}={ff[k]:.1f}s" for k in ("propfind", "head", "get", "drive") if k in ff)


def md5(b: bytes) -> str:
    return hashlib.md5(b).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--size", type=int, default=4096)
    parser.add_argument("--sizes", type=str, default=None,
                        help="comma-separated sizes to sweep (overrides --size)")
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--pattern", type=str, default=None, choices=list(PATTERNS),
                        help="run a lifecycle-pattern simulation instead of the size sweep")
    args = parser.parse_args()

    ts = time.strftime("%Y%m%d-%H%M%S")
    outdir = OUT_DIR / f"bench-verify-{ts}"
    outdir.mkdir(parents=True, exist_ok=True)

    log.info("Ensuring Nextcloud stack + rclone mount are up")
    nc.start()
    nc.reset_bruteforce()

    if args.pattern:
        results, all_timelines = run_pattern_matrix(args.pattern, args.iterations, args.timeout, outdir)
        (outdir / "summary.json").write_text(json.dumps(results, indent=2))
        (outdir / "timelines.json").write_text(json.dumps(all_timelines, indent=2))
        print(f"\n========== {args.pattern} summary ==========")
        wedged = [r["iter"] for r in results if r["wedged"]]
        zeros = [r["iter"] for r in results if r["target_zero_samples"]]
        phs = [r["iter"] for r in results if r["target_placeholder_window_s"]]
        bad_rb = [r["iter"] for r in results if r["readbacks"] and not all(x.get("ok") for x in r["readbacks"])]
        print(f"  iterations: {len(results)}")
        print(f"  wedged (control never full): {len(wedged)} {wedged if wedged else ''}")
        print(f"  target 0-byte/md5-less samples: {len(zeros)} {zeros if zeros else ''}")
        print(f"  target placeholder windows: {len(phs)} {phs if phs else ''}")
        print(f"  read-back mismatches: {len(bad_rb)} {bad_rb if bad_rb else ''}")
        poll = [r["poll_s"] for r in results if r["poll_s"]]
        if poll:
            print(f"  rclone lsjson poll latency: min={min(poll):.2f}s max={max(poll):.2f}s")
        nc.empty_drive_trash()
        print(f"\ndone. json={outdir}/")
        return

    sizes = [int(s) for s in args.sizes.split(",")] if args.sizes else [args.size]

    results = []
    all_timelines = {}
    run_no = 0
    for size in sizes:
        print(f"\n===== size {size}B =====")
        for _ in range(1, args.iterations + 1):
            run_no += 1
            result, timeline = run_iteration(run_no, size, args.timeout, outdir)
            result["size"] = size
            results.append(result)
            all_timelines[run_no] = dict(size=size, timeline=timeline)
            print(f"    first-full: {fmt_ff(result['first_full'])}"
                  f"   drive-seen={result['drive_seen_dt']}s"
                  f"   placeholder-window={result['drive_placeholder_window_s']}s"
                  f"   drive-zero={result['drive_zero_samples']}"
                  f"   head-odd={result['head_odd_samples']}"
                  f"   propfind-missing={result['propfind_missing_samples']}")

    (outdir / "summary.json").write_text(json.dumps(results, indent=2))
    (outdir / "timelines.json").write_text(json.dumps(all_timelines, indent=2))

    print("\n========== summary ==========")
    sigs = ("propfind", "head", "get", "drive")
    for size in sizes:
        group = [r for r in results if r["size"] == size]
        print(f"  size {size}B (n={len(group)}):")
        for sig in sigs:
            times = [r["first_full"].get(sig) for r in group if sig in r["first_full"]]
            if times:
                times.sort()
                print(f"    {sig:9s} first-full: min={times[0]:.1f}s  med={times[len(times)//2]:.1f}s  "
                      f"max={times[-1]:.1f}s  (n={len(times)}/{len(group)})")
            else:
                print(f"    {sig:9s} first-full: never (0/{len(group)})")
        ph = [r["drive_placeholder_window_s"] for r in group if r["drive_placeholder_window_s"] is not None]
        if ph:
            print(f"    drive placeholder window (0-byte/md5-less on Drive): min={min(ph):.1f}s max={max(ph):.1f}s")
        head_odd = sum(r["head_odd_samples"] for r in group)
        propfind_missing = sum(r["propfind_missing_samples"] for r in group)
        drive_zero = sum(r["drive_zero_samples"] for r in group)
        print(f"    transient anomalies: head={head_odd}  propfind-missing={propfind_missing}  drive-zero-samples={drive_zero}")

    poll = [r["drive_poll_s"] for r in results if r["drive_poll_s"]]
    if poll:
        print(f"\n  rclone lsjson poll latency: min={min(poll):.2f}s max={max(poll):.2f}s")

    # each iteration already purged its own folder; just empty the Drive bin
    nc.empty_drive_trash()
    print(f"\ndone. json={outdir}/")


if __name__ == "__main__":
    sys.stdout.reconfigure(line_buffering=True)
    main()
