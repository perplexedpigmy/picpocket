import logging
import shutil
import time
import uuid
from pathlib import Path

import pytest
import requests

from infra import nextcloud
from .utils import put_collection

logger = logging.getLogger(__name__)

PROBE_TIMEOUT = 10
FUSE_TO_WEBDAV_TIMEOUT = 30
WEBDAV_TO_FUSE_TIMEOUT = 180
PROBE_INTERVAL = 0.5


@pytest.fixture
def test_label(fuse_path) -> str:
    uid = uuid.uuid4().hex[:12]
    name = f"xl_{uid}"
    yield name
    d = fuse_path / name
    if d.exists():
        shutil.rmtree(str(d), ignore_errors=True)
    nextcloud.purge_remote_dir(name)


def _wait_for_webdav(ocr, path: str, expect_exists: bool, timeout: float = PROBE_TIMEOUT) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        exists = ocr.file_exists(f"/PicPocketTest/{path}")
        if exists == expect_exists:
            return True
        time.sleep(PROBE_INTERVAL)
    return False


def _dav_url(ocr, path: str) -> str:
    return f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{path}"


class TestFuseWriteWebdavRead:
    def test_write_then_read(self, ocr, fuse_path, test_label):
        p = fuse_path / test_label / "from_fuse.txt"
        p.parent.mkdir(parents=True)
        p.write_bytes(b"written via fuse")
        assert _wait_for_webdav(ocr, f"{test_label}/from_fuse.txt", True), \
            "File did not appear in WebDAV within timeout"
        resp = requests.get(_dav_url(ocr, f"{test_label}/from_fuse.txt"), auth=ocr.auth, timeout=10)
        assert resp.content == b"written via fuse"
        logger.info("FUSE→WebDAV propagation OK")

    def test_write_then_list(self, ocr, fuse_path, test_label):
        p = fuse_path / test_label / "list_test.txt"
        p.parent.mkdir(parents=True)
        p.write_bytes(b"x")
        assert _wait_for_webdav(ocr, f"{test_label}/list_test.txt", True), \
            "File not visible in WebDAV"
        files = ocr.list_files(f"/PicPocketTest/{test_label}")
        assert "list_test.txt" in files
        logger.info("FUSE write → WebDAV list OK")

    @pytest.mark.xfail(reason="FUSE delete propagation to Nextcloud WebDAV is not supported by Local external storage")
    def test_delete_then_propfind(self, ocr, fuse_path, test_label):
        p = fuse_path / test_label / "to_delete.txt"
        p.parent.mkdir(parents=True)
        p.write_bytes(b"delete me")
        assert _wait_for_webdav(ocr, f"{test_label}/to_delete.txt", True)
        p.unlink()
        assert _wait_for_webdav(ocr, f"{test_label}/to_delete.txt", False, timeout=FUSE_TO_WEBDAV_TIMEOUT), \
            "File still visible in WebDAV after FUSE delete"
        logger.info("FUSE delete → WebDAV gone OK")


class TestWebdavWriteFuseRead:
    def test_write_then_read(self, ocr, fuse_path, test_label):
        content = b"written via webdav"
        sub = f"{test_label}/from_webdav.txt"
        put_collection(_dav_url(ocr, sub), ocr.auth)
        requests.put(
            _dav_url(ocr, sub),
            data=content,
            auth=ocr.auth,
            timeout=10,
        )
        p = fuse_path / test_label / "from_webdav.txt"
        deadline = time.time() + WEBDAV_TO_FUSE_TIMEOUT
        found = None
        while time.time() < deadline:
            if p.exists():
                found = p.read_bytes()
                break
            time.sleep(PROBE_INTERVAL)
        assert found == content, f"WebDAV→FUSE propagation failed: got {found!r}"
        logger.info("WebDAV→FUSE propagation OK")

    def test_delete_then_stat(self, ocr, fuse_path, test_label):
        sub = f"{test_label}/webdav_delete.txt"
        p = fuse_path / test_label / "webdav_delete.txt"
        p.parent.mkdir(parents=True)
        p.write_bytes(b"to delete via webdav")
        assert _wait_for_webdav(ocr, sub, True)
        requests.delete(_dav_url(ocr, sub), auth=ocr.auth, timeout=10)
        deadline = time.time() + WEBDAV_TO_FUSE_TIMEOUT
        while time.time() < deadline:
            if not p.exists():
                break
            time.sleep(PROBE_INTERVAL)
        assert not p.exists(), "File still exists on FUSE after WebDAV delete"
        logger.info("WebDAV delete → FUSE gone OK")

    def test_list_after_webdav_upload(self, ocr, fuse_path, test_label):
        content = b"list check"
        sub = f"{test_label}/list_me.txt"
        put_collection(_dav_url(ocr, sub), ocr.auth)
        requests.put(_dav_url(ocr, sub), data=content, auth=ocr.auth, timeout=10)
        d = fuse_path / test_label
        deadline = time.time() + WEBDAV_TO_FUSE_TIMEOUT
        while time.time() < deadline:
            if d.exists() and any(d.iterdir()):
                break
            time.sleep(PROBE_INTERVAL)
        names = [f.name for f in d.iterdir()]
        assert "list_me.txt" in names
        logger.info("WebDAV upload → FUSE list OK")


class TestPropagationDelay:
    def test_measure_fuse_to_webdav(self, ocr, fuse_path, test_label):
        p = fuse_path / test_label / "timed.txt"
        p.parent.mkdir(parents=True)
        t0 = time.perf_counter()
        p.write_bytes(b"timed")
        deadline = time.time() + PROBE_TIMEOUT
        while time.time() < deadline:
            if ocr.file_exists(f"/PicPocketTest/{test_label}/timed.txt"):
                break
            time.sleep(PROBE_INTERVAL)
        elapsed = time.perf_counter() - t0
        logger.info("FUSE→WebDAV propagation delay: %.1fms", elapsed * 1000)

    def test_measure_webdav_to_fuse(self, ocr, fuse_path, test_label):
        sub = f"{test_label}/timed_wd.txt"
        put_collection(_dav_url(ocr, sub), ocr.auth)
        t0 = time.perf_counter()
        requests.put(
            _dav_url(ocr, sub),
            data=b"timed",
            auth=ocr.auth,
            timeout=10,
        )
        p = fuse_path / test_label / "timed_wd.txt"
        deadline = time.time() + WEBDAV_TO_FUSE_TIMEOUT
        while time.time() < deadline:
            if p.exists():
                break
            time.sleep(PROBE_INTERVAL)
        elapsed = time.perf_counter() - t0
        logger.info("WebDAV→FUSE propagation delay: %.1fms", elapsed * 1000)
