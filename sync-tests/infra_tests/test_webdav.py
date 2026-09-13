import logging
import uuid
from pathlib import Path

import pytest
import requests

logger = logging.getLogger(__name__)


@pytest.fixture
def ocr_clean(ocr):
    """Fixture that cleans up the test dir created by each test."""
    dirs_created = []

    def _make_test_path() -> str:
        uid = uuid.uuid4().hex[:12]
        sub = f"wdtest_{uid}"
        dirs_created.append(sub)
        return sub

    yield _make_test_path

    for sub in dirs_created:
        path = f"PicPocketTest/{sub}"
        try:
            for entry in ocr.list_files("/" + path):
                if entry and entry not in (".", ".."):
                    ocr.file_exists(f"{path}/{entry}")
                    r = requests.delete(
                        f"{ocr.base_url}/remote.php/dav/files/testuser/{path}/{entry}",
                        auth=ocr.auth,
                        timeout=10,
                    )
                    r.raise_for_status()
            r = requests.request(
                "DELETE",
                f"{ocr.base_url}/remote.php/dav/files/testuser/{path}",
                auth=ocr.auth,
                timeout=10,
            )
        except Exception as e:
            logger.warning("Cleanup failed for %s: %s", path, e)


class TestWebdavAuth:
    def test_401_without_credentials(self, ocr):
        resp = requests.request(
            "PROPFIND",
            f"{ocr.base_url}/remote.php/dav/files/testuser/",
            headers={"Depth": "0"},
            timeout=10,
        )
        assert resp.status_code == 401, f"Expected 401, got {resp.status_code}"

    def test_207_with_credentials(self, ocr):
        resp = requests.request(
            "PROPFIND",
            f"{ocr.base_url}/remote.php/dav/files/testuser/",
            auth=ocr.auth,
            headers={"Depth": "0"},
            timeout=10,
        )
        assert resp.status_code == 207, f"Expected 207, got {resp.status_code}"

    def test_mount_visible(self, ocr):
        files = ocr.list_files("/PicPocketTest")
        assert isinstance(files, list)
        logger.info("PicPocketTest contains %d entries", len(files))


from .utils import put_collection

class TestWebdavFileOps:
    def test_upload_download(self, ocr, ocr_clean):
        sub = ocr_clean()
        put_collection(
            f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{sub}",
            ocr.auth,
        )
        content = b"hello webdav"
        resp = requests.put(
            f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{sub}/test.txt",
            data=content,
            auth=ocr.auth,
            timeout=10,
        )
        assert resp.status_code in (201, 204), f"PUT failed: {resp.status_code}"
        get_resp = requests.get(
            f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{sub}/test.txt",
            auth=ocr.auth,
            timeout=10,
        )
        assert get_resp.status_code == 200
        assert get_resp.content == content
        logger.info("upload/download OK")

    def test_list_after_upload(self, ocr, ocr_clean):
        sub = ocr_clean()
        put_collection(
            f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{sub}",
            ocr.auth,
        )
        names = sorted(f"file_{i}.txt" for i in range(3))
        for name in names:
            requests.put(
                f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{sub}/{name}",
                data=b"x",
                auth=ocr.auth,
                timeout=10,
            )
        files = ocr.list_files(f"/PicPocketTest/{sub}")
        present = [f for f in files if f in names]
        assert len(present) == len(names), f"Expected {names}, got {files}"
        logger.info("list after upload OK: %s", present)

    def test_mkcol_delete(self, ocr, ocr_clean):
        sub = ocr_clean()
        put_collection(
            f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{sub}",
            ocr.auth,
        )
        path = f"PicPocketTest/{sub}/col"
        resp = requests.request(
            "MKCOL",
            f"{ocr.base_url}/remote.php/dav/files/testuser/{path}",
            auth=ocr.auth,
            timeout=10,
        )
        assert resp.status_code in (201, 405), f"MKCOL failed: {resp.status_code}"
        assert ocr.file_exists(f"/{path}")
        resp = requests.delete(
            f"{ocr.base_url}/remote.php/dav/files/testuser/{path}",
            auth=ocr.auth,
            timeout=10,
        )
        assert resp.status_code in (204, 404)
        assert not ocr.file_exists(f"/{path}")
        logger.info("MKCOL+DELETE OK")

    def test_overwrite(self, ocr, ocr_clean):
        sub = ocr_clean()
        put_collection(
            f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{sub}",
            ocr.auth,
        )
        url = f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{sub}/ov.txt"
        requests.put(url, data=b"first", auth=ocr.auth, timeout=10)
        requests.put(url, data=b"second", auth=ocr.auth, timeout=10)
        resp = requests.get(url, auth=ocr.auth, timeout=10)
        assert resp.content == b"second"
        logger.info("overwrite via WebDAV OK")

    def test_subdir_upload(self, ocr, ocr_clean):
        sub = ocr_clean()
        url = f"{ocr.base_url}/remote.php/dav/files/testuser/PicPocketTest/{sub}/a/b/c/f.txt"
        put_collection(url, ocr.auth)
        requests.put(url, data=b"nested", auth=ocr.auth, timeout=10)
        resp = requests.get(url, auth=ocr.auth, timeout=10)
        assert resp.content == b"nested"
        logger.info("subdir upload OK")
