import logging
import os
import random
import shutil
import string
import time
import uuid
from pathlib import Path

import pytest

logger = logging.getLogger(__name__)


@pytest.fixture
def test_dir(fuse_path) -> Path:
    uid = uuid.uuid4().hex[:12]
    path = fuse_path / f"test_{uid}"
    path.mkdir(parents=True, exist_ok=True)
    yield path
    shutil.rmtree(str(path), ignore_errors=True)
    logger.debug("Cleaned up %s", path)


def _random_bytes(size: int) -> bytes:
    return random.randbytes(size)


def _write_read_verify(path: Path, data: bytes) -> float:
    t0 = time.perf_counter()
    path.write_bytes(data)
    t1 = time.perf_counter()
    read = path.read_bytes()
    t2 = time.perf_counter()
    assert read == data, f"Content mismatch: wrote {len(data)}B, read back {len(read)}B"
    return t1 - t0, t2 - t1


class TestFuseWriteRead:
    def test_empty(self, test_dir):
        p = test_dir / "empty"
        write_lat, read_lat = _write_read_verify(p, b"")
        logger.info("empty: write=%.1fms read=%.1fms", write_lat * 1000, read_lat * 1000)

    def test_100b(self, test_dir):
        p = test_dir / "small.bin"
        data = _random_bytes(100)
        write_lat, read_lat = _write_read_verify(p, data)
        logger.info("100B: write=%.1fms read=%.1fms", write_lat * 1000, read_lat * 1000)

    def test_1k(self, test_dir):
        p = test_dir / "1k.bin"
        data = _random_bytes(1024)
        write_lat, read_lat = _write_read_verify(p, data)
        logger.info("1K: write=%.1fms read=%.1fms", write_lat * 1000, read_lat * 1000)

    def test_64k(self, test_dir):
        p = test_dir / "64k.bin"
        data = _random_bytes(65536)
        write_lat, read_lat = _write_read_verify(p, data)
        logger.info("64K: write=%.1fms read=%.1fms", write_lat * 1000, read_lat * 1000)

    def test_1m(self, test_dir):
        p = test_dir / "1m.bin"
        data = _random_bytes(1024 * 1024)
        write_lat, read_lat = _write_read_verify(p, data)
        logger.info("1M: write=%.1fms read=%.1fms", write_lat * 1000, read_lat * 1000)

    def test_10m(self, test_dir):
        p = test_dir / "10m.bin"
        data = _random_bytes(10 * 1024 * 1024)
        write_lat, read_lat = _write_read_verify(p, data)
        logger.info("10M: write=%.1fms read=%.1fms", write_lat * 1000, read_lat * 1000)

    def test_binary_content(self, test_dir):
        p = test_dir / "binary.png"
        header = b"\x89PNG\r\n\x1a\n" + _random_bytes(256)
        write_lat, read_lat = _write_read_verify(p, header)
        logger.info("binary header+256B: write=%.1fms read=%.1fms", write_lat * 1000, read_lat * 1000)

    def test_overwrite(self, test_dir):
        p = test_dir / "overwrite"
        p.write_bytes(b"first")
        p.write_bytes(b"second")
        assert p.read_bytes() == b"second"
        logger.info("overwrite OK")

    def test_delete_while_open(self, test_dir):
        p = test_dir / "while_open"
        p.write_bytes(b"data")
        fd = os.open(str(p), os.O_RDONLY)
        os.unlink(str(p))
        content = os.read(fd, 1024)
        os.close(fd)
        assert content == b"data"
        assert not p.exists()
        logger.info("delete-while-open: read succeeded, file gone after close")


class TestFuseDirOps:
    def test_create_remove(self, test_dir):
        d = test_dir / "subdir"
        d.mkdir()
        assert d.is_dir()
        d.rmdir()
        assert not d.exists()
        logger.info("mkdir+rmdir OK")

    def test_list_dir(self, test_dir):
        expected = sorted(f"file_{i}.txt" for i in range(10))
        for name in expected:
            (test_dir / name).write_bytes(b"x")
        got = sorted(f.name for f in test_dir.iterdir())
        assert got == expected
        logger.info("listdir: %d entries", len(expected))

    def test_rename(self, test_dir):
        src = test_dir / "old_name"
        dst = test_dir / "new_name"
        src.write_bytes(b"content")
        src.rename(dst)
        assert dst.read_bytes() == b"content"
        assert not src.exists()
        logger.info("rename OK")

    def test_stat(self, test_dir):
        p = test_dir / "stats"
        data = b"hello world"
        p.write_bytes(data)
        st = p.stat()
        assert st.st_size == len(data)
        assert st.st_mode & 0o777 != 0
        logger.info("stat: size=%d mode=0o%o", st.st_size, st.st_mode & 0o777)

    def test_deep_nested(self, test_dir):
        d = test_dir / "a" / "b" / "c"
        d.mkdir(parents=True)
        p = d / "deep.txt"
        p.write_bytes(b"deep")
        assert p.read_bytes() == b"deep"
        logger.info("deep nested dir OK")


class TestFuseErrors:
    def test_write_nonexistent_dir(self, test_dir):
        p = test_dir / "nonexistent" / "file"
        with pytest.raises(OSError):
            p.write_bytes(b"x")
        logger.info("write to nonexistent dir raises OSError")

    def test_read_nonexistent(self, test_dir):
        p = test_dir / "no_such_file"
        with pytest.raises(FileNotFoundError):
            p.read_bytes()
        logger.info("read nonexistent raises FileNotFoundError")

    def test_delete_nonexistent(self, test_dir):
        p = test_dir / "no_such_file"
        with pytest.raises(FileNotFoundError):
            p.unlink()
        logger.info("delete nonexistent raises FileNotFoundError")
