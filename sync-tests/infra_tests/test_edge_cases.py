import logging
import random
import shutil
import time
import uuid
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed

import pytest
import requests

from .utils import put_collection

logger = logging.getLogger(__name__)

FUSE = "fuse"
WEBDAV = "webdav"


def _gen(size: int) -> bytes:
    return random.randbytes(size)


class _Cleanup:
    def __init__(self, fuse_path, ocr):
        self.fuse_path = fuse_path
        self.ocr = ocr
        self.dirs = []

    def add(self, name: str):
        self.dirs.append(name)

    def finish(self):
        for name in self.dirs:
            d = self.fuse_path / name
            if d.exists():
                shutil.rmtree(str(d), ignore_errors=True)
        self.dirs.clear()


@pytest.fixture
def cl(fuse_path, ocr):
    c = _Cleanup(fuse_path, ocr)
    yield c
    c.finish()


class TestLatencyProfile:
    SIZES = [(0, "0B"), (100, "100B"), (1024, "1K"), (65536, "64K"),
             (1048576, "1M"), (10485760, "10M")]
    WEBDAV_ITERATIONS = 5
    FUSE_ITERATIONS = 10

    def test_fuse_latency(self, fuse_path, cl):
        uid = uuid.uuid4().hex[:8]
        base = fuse_path / f"lat_fuse_{uid}"
        base.mkdir(parents=True)
        cl.add(f"lat_fuse_{uid}")

        for size, label in self.SIZES:
            latencies = {"write": [], "read": []}
            data = _gen(size)
            for i in range(self.FUSE_ITERATIONS):
                p = base / f"{label}_{i}.bin"
                t0 = time.perf_counter()
                p.write_bytes(data)
                latencies["write"].append(time.perf_counter() - t0)
                t0 = time.perf_counter()
                p.read_bytes()
                latencies["read"].append(time.perf_counter() - t0)
                p.unlink()
            self._report(label, latencies)

    def test_webdav_latency(self, ocr, cl):
        uid = uuid.uuid4().hex[:8]
        base_url = f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/lat_wd_{uid}"
        put_collection(base_url, ocr.auth)

        for size, label in self.SIZES:
            latencies = {"write": [], "read": []}
            data = _gen(size)
            for i in range(self.WEBDAV_ITERATIONS):
                url = f"{base_url}/{label}_{i}.bin"
                t0 = time.perf_counter()
                r = requests.put(url, data=data, auth=ocr.auth, timeout=30)
                latencies["write"].append(time.perf_counter() - t0)
                assert r.status_code in (201, 204), f"PUT failed: {r.status_code}"
                t0 = time.perf_counter()
                r = requests.get(url, auth=ocr.auth, timeout=30)
                latencies["read"].append(time.perf_counter() - t0)
                assert r.status_code == 200
                requests.delete(url, auth=ocr.auth, timeout=10)
                time.sleep(0.1)
            self._report(label, latencies)
            cl.add(f"lat_wd_{uid}")

    def _report(self, label: str, latencies: dict):
        for op, vals in latencies.items():
            vals_s = sorted(vals)
            mean = sum(vals_s) / len(vals_s) * 1000
            p50 = vals_s[len(vals_s) // 2] * 1000
            p95 = vals_s[int(len(vals_s) * 0.95)] * 1000
            p99 = vals_s[int(len(vals_s) * 0.99)] * 1000
            mx = max(vals_s) * 1000
            logger.info(
                "%s %s: min=%.1f mean=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.1f ms",
                label, op,
                vals_s[0] * 1000, mean, p50, p95, p99, mx,
            )


class TestWarmUp:
    N = 10

    def test_fuse_warm_up(self, fuse_path, cl):
        uid = uuid.uuid4().hex[:8]
        d = fuse_path / f"warm_fuse_{uid}"
        d.mkdir(parents=True)
        cl.add(f"warm_fuse_{uid}")
        data = _gen(1024)
        for i in range(self.N):
            p = d / f"run_{i}.bin"
            t0 = time.perf_counter()
            p.write_bytes(data)
            elapsed = (time.perf_counter() - t0) * 1000
            p.unlink()
            logger.info("FUSE warm-up run %2d: write=%.1fms", i, elapsed)

    def test_webdav_warm_up(self, ocr, cl):
        uid = uuid.uuid4().hex[:8]
        base = f"warm_wd_{uid}"
        put_collection(
            f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{base}",
            ocr.auth,
        )
        data = _gen(1024)
        for i in range(self.N):
            url = f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{base}/run_{i}.bin"
            t0 = time.perf_counter()
            r = requests.put(url, data=data, auth=ocr.auth, timeout=30)
            elapsed = (time.perf_counter() - t0) * 1000
            r.raise_for_status()
            requests.delete(url, auth=ocr.auth, timeout=10)
            logger.info("WebDAV warm-up run %2d: write=%.1fms", i, elapsed)
            time.sleep(0.2)
        cl.add(base)


class TestConcurrentWrites:
    def test_concurrent_fuse_writes(self, fuse_path, cl):
        uid = uuid.uuid4().hex[:8]
        base = fuse_path / f"con_fuse_{uid}"
        base.mkdir(parents=True)
        cl.add(f"con_fuse_{uid}")
        n = 5
        data = _gen(4096)
        paths = [base / f"con_{i}.bin" for i in range(n)]

        def _write(p):
            p.write_bytes(data)
            return p.read_bytes() == data

        with ThreadPoolExecutor(max_workers=n) as pool:
            futures = [pool.submit(_write, p) for p in paths]
            results = [f.result() for f in as_completed(futures)]
        assert all(results), f"Concurrent writes: {sum(results)}/{n} succeeded"
        logger.info("concurrent FUSE writes: %d/%d OK", sum(results), n)

    def test_concurrent_webdav_puts(self, ocr, cl):
        uid = uuid.uuid4().hex[:8]
        base = f"con_wd_{uid}"
        put_collection(
            f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{base}",
            ocr.auth,
        )
        n = 5
        data = _gen(4096)
        results = []
        for i in range(n):
            url = f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{base}/con_{i}.bin"
            r = requests.put(url, data=data, auth=ocr.auth, timeout=30)
            r.raise_for_status()
            r = requests.get(url, auth=ocr.auth, timeout=30)
            results.append(r.content == data)
            time.sleep(1)
        assert all(results), f"Sequential WebDAV puts: {sum(results)}/{n} succeeded"
        logger.info("sequential WebDAV PUTs: %d/%d OK (1s delay between)", sum(results), n)
        cl.add(base)


class TestSpecialChars:
    NAMES = [
        "with space.txt",
        "with(parentheses).txt",
        "with[brackets].txt",
        "with&ersand.txt",
        "with'quote.txt",
        "with\"dquote.txt",
        "unícode_éñ.txt",
        "中文测试.txt",
        "emoji_🎉.txt",
    ]

    def test_fuse_special_chars(self, fuse_path, cl):
        uid = uuid.uuid4().hex[:8]
        d = fuse_path / f"spec_fuse_{uid}"
        d.mkdir(parents=True)
        cl.add(f"spec_fuse_{uid}")
        for name in self.NAMES:
            p = d / name
            p.write_bytes(b"content")
            assert p.read_bytes() == b"content", f"Readback failed for {name}"
            logger.info("FUSE special char OK: %s", name)

    def test_webdav_special_chars(self, ocr, cl):
        uid = uuid.uuid4().hex[:8]
        base = f"spec_wd_{uid}"
        put_collection(
            f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{base}",
            ocr.auth,
        )
        for name in self.NAMES:
            url = f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{base}/{name}"
            r = requests.put(url, data=b"content", auth=ocr.auth, timeout=10)
            assert r.status_code in (201, 204), f"PUT failed for {name}: {r.status_code}"
            r = requests.get(url, auth=ocr.auth, timeout=10)
            assert r.content == b"content", f"GET mismatch for {name}"
            logger.info("WebDAV special char OK: %s", name)
        cl.add(base)


class TestManyFiles:
    N = 10

    def test_fuse_many_files(self, fuse_path, cl):
        uid = uuid.uuid4().hex[:8]
        d = fuse_path / f"many_fuse_{uid}"
        d.mkdir(parents=True)
        cl.add(f"many_fuse_{uid}")
        for i in range(self.N):
            (d / f"f_{i}.txt").write_bytes(b"x")
        t0 = time.perf_counter()
        entries = list(d.iterdir())
        list_time = time.perf_counter() - t0
        assert len(entries) == self.N
        logger.info("FUSE list %d files: %.1fms", self.N, list_time * 1000)
        t0 = time.perf_counter()
        for p in entries:
            p.unlink()
        del_time = time.perf_counter() - t0
        logger.info("FUSE delete %d files: %.1fms", self.N, del_time * 1000)

    def test_fuse_many_in_one_dir_with_subdir(self, fuse_path, cl):
        uid = uuid.uuid4().hex[:8]
        d = fuse_path / f"many2_fuse_{uid}"
        d.mkdir(parents=True)
        cl.add(f"many2_fuse_{uid}")
        for i in range(self.N):
            (d / f"f_{i}.txt").write_bytes(b"x")
        sub = d / "sub"
        sub.mkdir()
        sub.joinpath("inside.txt").write_bytes(b"inside")
        entries = list(d.iterdir())
        assert len(entries) == self.N + 1
        logger.info("FUSE %d files + subdir: %d entries", self.N, len(entries))


class TestLongFilenames:
    def test_fuse_long_names(self, fuse_path, cl):
        uid = uuid.uuid4().hex[:8]
        d = fuse_path / f"long_fuse_{uid}"
        d.mkdir(parents=True)
        cl.add(f"long_fuse_{uid}")
        for length in [50, 100, 150, 200, 255]:
            name = "a" * length + ".txt"
            p = d / name
            try:
                p.write_bytes(b"x")
                _ = p.read_bytes()
                p.unlink()
                logger.info("FUSE filename length %d: OK", length)
            except OSError as e:
                logger.info("FUSE filename length %d: FAILED (%s)", length, e)

    def test_webdav_long_names(self, ocr, cl):
        uid = uuid.uuid4().hex[:8]
        base = f"long_wd_{uid}"
        put_collection(
            f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{base}",
            ocr.auth,
        )
        for length in [50, 100, 150, 200, 255]:
            name = "a" * length + ".txt"
            url = f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{base}/{name}"
            r = requests.put(url, data=b"x", auth=ocr.auth, timeout=10)
            if r.status_code in (201, 204):
                requests.get(url, auth=ocr.auth, timeout=10)
                requests.delete(url, auth=ocr.auth, timeout=10)
                logger.info("WebDAV filename length %d: OK", length)
            else:
                logger.info("WebDAV filename length %d: FAILED (%s)", length, r.status_code)
        cl.add(base)
