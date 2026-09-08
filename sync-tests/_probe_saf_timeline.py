#!/usr/bin/env python3
"""Server-side timeline probe for SAF writes, with two modes.

probe mode (default):
  Drives the androidTest SAFWriteProbeTest on an emulator while sampling the
  Nextcloud WebDAV server for the probe files every ~200ms (PROPFIND size +
  GET ground truth), then dumps logcat for SAFProbe + the client uploads.

observe mode (--observe):
  Watches the whole PicPocketTest tree while the APP syncs (run pytest in
  parallel). Samples every ~250ms, captures GET ground truth for every file,
  watches logcat for the app's sync error, and prints the server state at the
  exact moment of the error.

Usage:
  python3 _probe_saf_timeline.py [--duration 120] [--serial emulator-5554]
  python3 _probe_saf_timeline.py --observe --duration 300 --serial emulator-5554
"""

import argparse
import hashlib
import json
import logging
import re
import signal
import subprocess
import sys
import time
from pathlib import Path

from _debug_primitives import webdav_get, webdav_propfind_depth1, webdav_delete
from infra import nextcloud as nc

log = logging.getLogger("saf_probe")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s.%(msecs)03d [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)

SETTLED_WAIT = 0.0
SETTLED_EQ = False

OUT_DIR = Path(__file__).parent / "tmp"

PROBES = [
    "probeA.bin",
    "probeB.bin",
    "probeD.bin",
    "probeRwt.bin",
    "probeRecreate.bin",
    "probePlaceholder.bin",
    "probeRapid.bin",
    "probePaced.bin",
]

C1 = bytes(i % 251 for i in range(131))
C2 = bytes((i * 7 + 3) % 251 for i in range(131))
C3 = bytes((i * 13 + 5) % 251 for i in range(3779))

# Files that must end holding their full payload; probeA is create-only (0/absent
# expected), probeD must be absent (rollback delete).
EXPECT_SIZES = {
    "probeB.bin": 131,
    "probeRwt.bin": 131,
    "probeRecreate.bin": 131,
    "probePlaceholder.bin": 131,
    "probeRapid.bin": 3779,
    "probePaced.bin": 3779,
}

TEST_CLASS = "com.picpocket.app.drive.SAFWriteProbeTest"
RUNNER = "com.picpocket.app.test/androidx.test.runner.AndroidJUnitRunner"

SERIAL = "emulator-5554"

DIR_MIME = "httpd/unix-directory"

SENTINELS = (
    "could not be read",
    "CorruptRegistry",
)


def adb(*args: str):
    return subprocess.run(
        ["adb", "-s", SERIAL, *args], capture_output=True, text=True, timeout=120,
    )


def sample_root() -> dict:
    listing = webdav_propfind_depth1("PicPocketTest")
    out = {}
    for name in PROBES:
        row = listing.get(name)
        p_size = row[0] if row else None
        try:
            st, content = webdav_get(f"PicPocketTest/{name}")
        except Exception as e:
            st, content = None, b""
            log.warning("GET %s failed: %s", name, e)
        if st == 200 and content:
            out[name] = (p_size, len(content), hashlib.md5(content).hexdigest())
        elif st == 200:
            out[name] = (p_size, 0, None)
        else:
            out[name] = (p_size, None, None)
    return out


def drive_probe() -> dict:
    """Drive-side state of the probe files via rclone lsjson: {name: (Size, md5)}."""
    res = subprocess.run(
        ["rclone", "lsjson", "--recursive", "--hash",
         f"{nc.RCLONE_REMOTE}:{nc.RCLONE_REMOTE_PATH}"],
        capture_output=True, text=True, timeout=60,
    )
    if res.returncode != 0:
        log.warning("drive_probe rclone rc=%s stderr=%r", res.returncode, res.stderr[:200])
        return {}
    try:
        rows = json.loads(res.stdout)
    except json.JSONDecodeError:
        return {}
    out = {}
    for r in rows:
        if r.get("IsDir"):
            continue
        p = r.get("Path")
        if p in PROBES:
            out[p] = (r.get("Size"), (r.get("Hashes") or {}).get("md5"))
    return out


def watch_once() -> dict:
    """Sample the whole PicPocketTest tree.

    Returns {relative_path: (PROPFIND_size, GET_len, md5[:8])}. A directory is
    recursed into (PROPFIND + GET of its files); GET_len None = not retrievable
    (yet), 0 = genuinely zero bytes.
    """
    out = {}
    try:
        root = webdav_propfind_depth1("PicPocketTest")
    except Exception as e:
        log.warning("watch_once root PROPFIND failed: %s", e)
        return out
    for name, (size, mime, _etag) in root.items():
        if mime == DIR_MIME:
            try:
                sub = webdav_propfind_depth1(f"PicPocketTest/{name}")
            except Exception:
                continue
            for fname, (fsize, fmime, _fe) in sub.items():
                if fmime == DIR_MIME:
                    continue
                out[f"{name}/{fname}"] = _get_entry(f"PicPocketTest/{name}/{fname}", fsize)
        else:
            out[name] = _get_entry(f"PicPocketTest/{name}", size)
    return out


def _get_entry(path: str, p_size):
    try:
        st, content = webdav_get(path)
    except Exception as e:
        log.warning("GET %s failed: %s", path, e)
        return (p_size, None, None)
    if st == 200 and content:
        return (p_size, len(content), hashlib.md5(content).hexdigest()[:8])
    if st == 200:
        return (p_size, 0, None)
    return (p_size, None, None)


def fmt(entry) -> str:
    if entry is None:
        return "absent"
    p, g, m = entry
    if g is None:
        return f"P={p}  GET=none"
    return f"P={p}  GET={g}  md5={m or '-'}"


def logcat_sentinel():
    out = adb("logcat", "-d", "-t", "500").stdout or ""
    for line in out.splitlines():
        if any(s in line for s in SENTINELS):
            return line
    return None


def nearest_state(rows, t, path):
    best = None
    best_dt = None
    for dt, s in rows:
        if abs(dt - t) < (best_dt if best_dt is not None else float("inf")):
            best_dt = abs(dt - t)
            best = s.get(path)
    return best, best_dt


def print_timeline(rows, paths):
    print("\n========== server timeline ==========")
    for path in sorted(paths):
        print(f"\n-- {path} --")
        prev = None
        for dt, s in rows:
            st = fmt(s.get(path))
            if st == prev:
                continue
            prev = st
            print(f"  +{dt:6.2f}s  {st}")


def dump_logcat(outdir):
    logcat = adb("logcat", "-d").stdout or ""
    lines = [
        l for l in logcat.splitlines()
        if "SAFProbe" in l
        or "SyncManager" in l
        or "DeviceRegistry" in l
        or "DriveFileManager" in l
        or "nextcloud" in l.lower()
        or "DocumentsStorageProvider" in l
        or "FileUploadHelper" in l
        or "UploadFileOperation" in l
        or "SynchronizeFileOperation" in l
    ]
    (outdir / "logcat.txt").write_text("\n".join(lines))
    print(f"\n========== logcat ({len(lines)} lines, last 160) ==========")
    for l in lines[-160:]:
        print(l)
    return lines


def run_observe(duration: float, outdir: Path, interval: float = 0.25):
    adb("logcat", "-c")
    time.sleep(0.5)
    rows = []
    t0 = time.time()
    sentinel_line = None
    sentinel_t = None
    last_lc = 0.0
    stop = False

    def _stop(*_a):
        nonlocal stop
        stop = True

    signal.signal(signal.SIGINT, _stop)
    log.info("observe: watching PicPocketTest on %s (max %.0fs, interval %.1fs) — start the app sync now", SERIAL, duration, interval)
    while not stop and time.time() - t0 < duration:
        rows.append((round(time.time() - t0, 2), watch_once()))
        now = time.time()
        if now - last_lc >= 1.5:
            last_lc = now
            ln = logcat_sentinel()
            if ln:
                if sentinel_line is None:
                    sentinel_line = ln
                    sentinel_t = now
                    log.info("observe: SENTINEL @ +%.1fs: %s", now - t0, ln)
                elif now - sentinel_t > 8:
                    break
        time.sleep(interval)
    if stop:
        log.info("observe: stopping early on SIGINT (%d samples)", len(rows))

    (outdir / "timeline.json").write_text(json.dumps(rows, indent=2))

    all_paths = set()
    for _, s in rows:
        all_paths.update(s.keys())
    print_timeline(rows, all_paths)

    lines = dump_logcat(outdir)

    if sentinel_line:
        print("\n========== state at app error moment ==========")
        print(f"logcat: {sentinel_line}")
        offset = sentinel_t - t0
        print(f"(error detected ~ +{offset:.1f}s of observe)")
        for path in sorted(all_paths):
            st, dt = nearest_state(rows, offset, path)
            print(f"  {path}: {fmt(st)}  (nearest +{dt:.2f}s)")
    else:
        print("\n(no app sync error sentinel observed — see logcat.txt)")

    print(f"\ndone. json={outdir}/timeline.json  logcat={outdir}/logcat.txt")


def verdict(outdir: Path, rows, settled: dict) -> dict:
    """Host-side ground truth per probe.

    ``settled`` is a *quiet* watch_once snapshot taken after the uploader
    drained (see wait_settle). Judging from the raw last sample is wrong:
    the Nextcloud client uploads files sequentially, so a probe written late
    can still be zero-sized at sampling time even though the server ends
    correct — that lag, not the SAF write, produced the earlier 0-byte verdicts.

    This is the authoritative check (the app's own verify-by-cached-length is
    the thing we suspect of being fooled, so it plays no part here).
    """
    final = {}
    transient_zero = {}
    for name in PROBES:
        zeros = 0
        for _dt, s, _d in rows:
            p, g, m = s.get(name, (None, None, None))
            if p == 0 or g == 0:
                zeros += 1
        se = settled.get(name)
        final[name] = (se[0], se[1]) if se else None
        transient_zero[name] = zeros

    fmt_final = {
        n: (f"P={v[0]} GET={v[1]}" if v is not None else "absent")
        for n, v in final.items()
    }

    trace_ok = {}
    for n in sorted(EXPECT_SIZES):
        v = final.get(n)
        ok = v is not None and v[1] == EXPECT_SIZES[n]
        trace_ok[n] = ok

    # probeD.bin ends deleted (failed-write rollback) -> must be absent.
    d_ok = final.get("probeD.bin") is None

    # probeA.bin is create-only -> 0-byte or absent is expected, not a bug.

    burst = {k: v for k, v in settled.items() if k.startswith("burst_")}
    burst_bad = sorted(
        k for k, v in burst.items() if not (v[1] is not None and v[1] == 131)
    )

    res = dict(
        final=fmt_final,
        transient_zero=transient_zero,
        trace_ok=trace_ok,
        probeD_removed=d_ok,
        burst_written=len(burst),
        burst_bad=burst_bad,
        burst_expected_each=131,
        settled_after_eq=SETTLED_EQ,
        settled_wait_s=round(SETTLED_WAIT, 1),
    )
    (outdir / "verdict.json").write_text(json.dumps(res, indent=2))
    print("\n========== VERDICT (host ground truth, settled state) ==========")
    print(json.dumps(res, indent=2))
    return res


def wait_settle(outdir: Path, timeout: float = 90.0, quiet: int = 6) -> dict:
    """Sample the tree until the server state goes quiet (uploads drained).

    Returns the last watch_once snapshot. The Nextcloud client uploads files
    sequentially, so a raw "last sample" right after the test over-counts
    still-draining files as zero bytes.
    """
    global SETTLED_WAIT, SETTLED_EQ
    t0 = time.time()
    last_snap: dict = {}
    last_state = None
    quiet_count = 0
    while time.time() - t0 < timeout:
        snap = watch_once()
        if snap:
            last_snap = snap
            state = tuple(sorted((k, v[1]) for k, v in snap.items()))
            if state == last_state:
                quiet_count += 1
            else:
                quiet_count = 0
            last_state = state
        SETTLED_WAIT = time.time() - t0
        if quiet_count >= quiet and last_snap:
            break
        time.sleep(1.0)
    SETTLED_EQ = quiet_count >= quiet or bool(last_snap)
    return last_snap


def run_probe(duration: float, outdir: Path):
    installed = adb("shell", "pm", "list", "packages").stdout
    if "com.picpocket.app" not in installed:
        log.error("app APK not installed on %s; run: ./gradlew assembleDebug && adb -s %s install -r app/build/outputs/apk/debug/app-debug.apk", SERIAL, SERIAL)
        sys.exit(1)
    if "com.picpocket.app.test" not in installed:
        log.error("androidTest APK not installed on %s; run: ./gradlew assembleDebugAndroidTest && adb -s %s install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", SERIAL, SERIAL)
        sys.exit(1)

    for name in PROBES:
        webdav_delete(f"PicPocketTest/{name}")
    time.sleep(0.5)

    adb("logcat", "-c")
    time.sleep(0.5)

    rows = []
    t0 = time.time()
    log.info("Launching %s on %s", TEST_CLASS, SERIAL)
    proc = subprocess.Popen(
        ["adb", "-s", SERIAL, "shell", "am", "instrument", "-w",
         "-e", "class", TEST_CLASS, RUNNER],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    while proc.poll() is None and time.time() - t0 < duration:
        rows.append((round(time.time() - t0, 2), sample_root(), drive_probe()))
        time.sleep(0.2)
    for _ in range(10):
        rows.append((round(time.time() - t0, 2), sample_root(), drive_probe()))
        time.sleep(0.2)
    try:
        proc_out, _ = proc.communicate(timeout=30)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc_out = proc.stdout.read() if proc.stdout else "(no output)"
    # Keep watching until the server state goes quiet: the Nextcloud client
    # uploads files sequentially, so the test can end while later writes are
    # still draining. Judging before that drains over-counts 0-byte files.
    log.info("test finished; waiting for server state to settle (uploads drain)")
    settled = wait_settle(outdir)

    (outdir / "timeline.json").write_text(json.dumps(rows, indent=2))

    verdict(outdir, rows, settled)

    print("\n========== instrument output ==========")
    print(proc_out)

    print("\n========== server timeline ==========")
    print(f"expected md5: C1={hashlib.md5(C1).hexdigest()[:8]}  C2={hashlib.md5(C2).hexdigest()[:8]}  C3={hashlib.md5(C3).hexdigest()[:8]}")
    for name in PROBES:
        print(f"\n-- {name} --")
        prev = None
        for dt, s, d in rows:
            p, g, gmd5 = s.get(name, (None, None, None))
            dv = d.get(name)
            dv_s = f"DRIVE={dv[0]},{str(dv[1])[:8]}" if dv else "DRIVE=absent"
            state = f"P={p}  GET={g}  md5={(gmd5 or '')[:8]}  {dv_s}"
            if state == prev:
                continue
            prev = state
            print(f"  +{dt:6.2f}s  {state}")

    dump_logcat(outdir)
    print(f"\ndone. json={outdir}/timeline.json  logcat={outdir}/logcat.txt")


def main():
    global SERIAL
    parser = argparse.ArgumentParser()
    parser.add_argument("--duration", type=float, default=120.0)
    parser.add_argument("--interval", type=float, default=0.25)
    parser.add_argument("--serial", default=SERIAL)
    parser.add_argument("--observe", action="store_true", help="watch the tree while the app syncs")
    parser.add_argument("--no-nc-start", action="store_true", help="skip starting the Nextcloud stack (pytest owns it)")
    args = parser.parse_args()
    SERIAL = args.serial

    if not args.no_nc_start:
        nc.start()
        nc.reset_bruteforce()

    ts = time.strftime("%Y%m%d-%H%M%S")
    outdir = OUT_DIR / (f"safwatch-{ts}" if args.observe else f"safprobe-{ts}")
    outdir.mkdir(parents=True, exist_ok=True)

    if args.observe:
        run_observe(args.duration, outdir, interval=args.interval)
    else:
        run_probe(args.duration, outdir)


if __name__ == "__main__":
    main()
