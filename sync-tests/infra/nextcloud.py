"""Nextcloud test infrastructure management."""

import json
import logging
import shutil
import subprocess
import time
from pathlib import Path

logger = logging.getLogger(__name__)

COMPOSE_FILE = Path(__file__).parent.parent / "docker-compose.test.yml"
NEXTCLOUD_CONTAINER = "sync-tests-nextcloud-1"
PASS_PREFIX = "app/picpocket/tester"
NCDATA_MOUNT_POINT = Path(__file__).parent.parent.parent / "tmp" / "nc_data"

OCC = ["php", "/var/www/html/occ"]


def _run(cmd: list[str], check: bool = True, **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, check=check, **kwargs)


def _fail(msg: str, result: subprocess.CompletedProcess) -> str:
    """Format a failure message from a command result (stdout or stderr)."""
    detail = (result.stderr or result.stdout or "").strip()
    return f"{msg}: {detail}" if detail else msg


def _pass_get(key: str) -> str:
    result = _run(["pass", f"{PASS_PREFIX}/{key}"])
    return result.stdout.strip()


def _wait_for_healthy(timeout: int = 300) -> None:
    start = time.time()
    while time.time() < start + timeout:
        try:
            result = _run(
                ["docker", "exec", NEXTCLOUD_CONTAINER] + OCC + ["status"],
                check=False,
            )
            if "installed: true" in result.stdout:
                logger.info("Nextcloud is healthy")
                return
            logger.debug("Nextcloud not installed yet: %s", result.stdout.strip())
        except subprocess.CalledProcessError as e:
            logger.debug("Health check failed: %s", e)
        time.sleep(5)
    raise TimeoutError(f"Nextcloud not healthy after {timeout}s")


def _occ(args: list[str], check: bool = False) -> subprocess.CompletedProcess:
    return _run(
        ["docker", "exec", NEXTCLOUD_CONTAINER] + OCC + args,
        check=check,
    )


def _get_picpockettest_mounts() -> list[dict]:
    result = _occ(["files_external:list", "--output=json"])
    if result.returncode != 0:
        logger.error(_fail("Failed to list external mounts", result))
        return []
    try:
        mounts = json.loads(result.stdout)
        return [m for m in mounts if m.get("mount_point") == "/PicPocketTest"]
    except json.JSONDecodeError as e:
        logger.error("Failed to parse mount list: %s", e)
        return []


def _configure_local_mount() -> None:
    existing = _get_picpockettest_mounts()
    if existing:
        mount_id = existing[0]["mount_id"]
        logger.info(
            "Mount %d for PicPocketTest already exists (total %d duplicates)",
            mount_id, len(existing),
        )
    else:
        result = _occ([
            "files_external:create",
            "PicPocketTest",
            "local",
            "null::null",
            "-c", "datadir=/mnt/gdrive",
        ])
        if result.returncode != 0:
            logger.error(_fail("Failed to create PicPocketTest mount", result))
            return
        logger.info("Created PicPocketTest mount")
        mounts = _get_picpockettest_mounts()
        if not mounts:
            logger.error("PicPocketTest mount not found after creation")
            return
        mount_id = mounts[0]["mount_id"]

    result = _occ(["files_external:applicable", str(mount_id), "--add-user", "testuser"])
    if result.returncode != 0:
        logger.error(_fail(f"Failed to assign mount {mount_id} to testuser", result))
        return

    # Verify assignment
    mounts = _get_picpockettest_mounts()
    updated = [m for m in mounts if "testuser" in m.get("applicable_users", [])]
    if updated:
        logger.info("Mount %d assigned to testuser: verified", mount_id)
    else:
        logger.warning(
            "Mount %d assigned but applicable_users not reflected: %s",
            mount_id, mounts,
        )


RCLONE_MOUNT_POINT = Path(__file__).parent.parent.parent / "tmp" / "gdrive-test"
RCLONE_REMOTE = "gtest"
RCLONE_REMOTE_PATH = "PicPocketTest"
RCLONE_DIR_CACHE_TIME = 10
DOCKERFILE_PATH = Path(__file__).parent.parent / "Dockerfile.nextcloud-rclone"
BUILD_CONTEXT = Path(__file__).parent.parent
IMAGE_TAG = "nextcloud-rclone:test"


def _is_rclone_mounted() -> bool:
    """Check if rclone mount is already active at RCLONE_MOUNT_POINT."""
    if not RCLONE_MOUNT_POINT.exists():
        return False
    result = _run(["mountpoint", "-q", str(RCLONE_MOUNT_POINT)], check=False)
    return result.returncode == 0


def _container_mount_ok() -> bool:
    """Check the running Nextcloud container can actually read /mnt/gdrive."""
    result = _run(
        ["docker", "exec", NEXTCLOUD_CONTAINER, "ls", "/mnt/gdrive/"],
        check=False,
    )
    if result.returncode == 0:
        return True
    logger.warning(
        "Nextcloud container cannot read /mnt/gdrive (%s) — restarting it",
        (result.stderr or result.stdout or "").strip(),
    )
    _run(["docker", "restart", NEXTCLOUD_CONTAINER], check=False)
    time.sleep(10)
    result = _run(
        ["docker", "exec", NEXTCLOUD_CONTAINER, "ls", "/mnt/gdrive/"],
        check=False,
    )
    return result.returncode == 0


def _verify_rclone_io() -> bool:
    """Write a test file to the mount and read it back to verify I/O works."""
    test_file = RCLONE_MOUNT_POINT / ".rclone_verify"
    try:
        test_file.write_text("verify")
        content = test_file.read_text()
        test_file.unlink()
        if content == "verify":
            return True
        logger.error("rclone readback mismatch: got %r", content)
        return False
    except OSError as e:
        # Clean up if file was created but read failed
        if test_file.exists():
            test_file.unlink(missing_ok=True)
        logger.error("rclone I/O error: %s", e)
        return False


def _ensure_rclone_mount() -> None:
    """Mount rclone to the test mount point if not already mounted."""
    mounted = _is_rclone_mounted()
    if mounted:
        logger.info("rclone process running at %s", RCLONE_MOUNT_POINT)
    else:
        RCLONE_MOUNT_POINT.mkdir(parents=True, exist_ok=True)
        VFS_CACHE_DIR = RCLONE_MOUNT_POINT.parent / "rclone-vfs-cache"
        VFS_CACHE_DIR.mkdir(parents=True, exist_ok=True)
        result = _run(
            [
                "rclone", "mount",
                f"{RCLONE_REMOTE}:{RCLONE_REMOTE_PATH}",
                str(RCLONE_MOUNT_POINT),
                "--daemon",
                "--allow-other",
                "--uid=33", "--gid=33",
                "--vfs-cache-mode", "writes",
                "--cache-dir", str(VFS_CACHE_DIR),
                "--dir-cache-time", f"{RCLONE_DIR_CACHE_TIME}s",
                "--poll-interval", "0",
            ],
            check=False,
        )
        if result.returncode != 0:
            if _is_rclone_mounted():
                logger.info("rclone already mounted at %s", RCLONE_MOUNT_POINT)
            else:
                logger.error(_fail("rclone mount failed", result))
                raise RuntimeError(_fail("rclone mount failed", result))
        time.sleep(2)
        if not _is_rclone_mounted():
            logger.error("rclone mount not active after mount command")
            return
        logger.info("rclone mounted at %s", RCLONE_MOUNT_POINT)

    if _verify_rclone_io():
        logger.info("rclone I/O verified (write+read OK)")
    else:
        raise RuntimeError(
            f"rclone mount at {RCLONE_MOUNT_POINT} failed readback check — "
            "Google Drive may not be accessible"
        )


def drive_state() -> list[dict]:
    """Recursively list the Drive PicPocketTest folder via rclone lsjson.

    Reads Google Drive directly (independent of the FUSE mount), returning
    file rows with Size, MimeType and MD5 keys.
    """
    result = _run(
        ["rclone", "lsjson", "--recursive", "--hash", f"{RCLONE_REMOTE}:{RCLONE_REMOTE_PATH}"],
        check=False,
    )
    if result.returncode != 0:
        logger.warning(
            "rclone lsjson failed (rc=%d): %s",
            result.returncode, (result.stderr or result.stdout or "").strip(),
        )
        return []
    try:
        rows = json.loads(result.stdout)
    except json.JSONDecodeError as e:
        logger.warning("rclone lsjson parse error: %s", e)
        return []
    return [r for r in rows if not r.get("IsDir")]


def _drive_listing() -> list[dict]:
    """Raw recursive lsjson of the Drive PicPocketTest folder, INCLUDING dirs.

    Used for emptiness checks where directories must not be ignored.
    Returns [] on any failure (like drive_state, but without the IsDir filter).
    """
    result = _run(
        ["rclone", "lsjson", "--recursive", f"{RCLONE_REMOTE}:{RCLONE_REMOTE_PATH}"],
        check=False,
    )
    if result.returncode != 0:
        logger.warning(
            "rclone lsjson failed (rc=%d): %s",
            result.returncode, (result.stderr or result.stdout or "").strip(),
        )
        return []
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as e:
        logger.warning("rclone lsjson parse error: %s", e)
        return []


def purge_drive(timeout: int = 120) -> None:
    """Definitively empty PicPocketTest on Google Drive and reconcile Nextcloud.

    Deletes through the FUSE mount (rmtree/unlink on the mount point) so the
    mount's VFS/dir cache and the remote folder ID stay in sync. An out-of-band
    `rclone purge` + `mkdir` would recreate the folder under a new Drive ID,
    desync the running mount, and silently break all subsequent uploads, so it
    must not be used. Includes empty directories. Re-scans Nextcloud so no
    phantom filecache entries remain. Raises TimeoutError if Drive stays
    non-empty.
    """
    def _delete_children() -> None:
        if not RCLONE_MOUNT_POINT.exists():
            return
        for child in list(RCLONE_MOUNT_POINT.iterdir()):
            if child.is_dir() and not child.is_symlink():
                shutil.rmtree(child)
            else:
                child.unlink(missing_ok=True)

    _delete_children()
    deadline = time.time() + timeout
    while _drive_listing() and time.time() < deadline:
        logger.warning("Drive not empty after delete, retrying")
        _delete_children()
        time.sleep(5)
    rows = _drive_listing()
    if rows:
        names = [r.get("Path", r.get("Name", "?")) for r in rows[:10]]
        raise TimeoutError(f"Drive PicPocketTest not empty after purge: {names}")
    _occ(["files:scan", "--all"], check=False)
    logger.info("Drive PicPocketTest purged; Nextcloud re-scanned")


def wait_drive_finalized(path: str, min_size: int = 0, timeout: float = 180.0) -> list[dict]:
    """Wait until the Drive copy of path is fully uploaded and finalized.

    A file is finalized when its MIME type is not the unfinalized-upload
    marker 'application/x-partial-download', its size is >= min_size, and it
    has a real MD5 (i.e. rclone could fully read it). Returns the final
    matching lsjson rows (possibly incomplete on timeout).
    """
    deadline = time.time() + timeout
    last: list[dict] = []
    while True:
        rows = drive_state()
        if path in ("", "/"):
            last = rows
        else:
            prefix = path.rstrip("/") + "/"
            last = [r for r in rows if r.get("Path") == path or r.get("Path", "").startswith(prefix)]
        if last and all(
            r.get("MimeType") != "application/x-partial-download"
            and r.get("Size", 0) >= min_size
            and (r.get("Hashes") or {}).get("md5")
            for r in last
        ):
            return last
        if time.time() >= deadline:
            logger.warning(
                "wait_drive_finalized %r timed out after %.0fs: %d file(s)",
                path, timeout, len(last),
            )
            return last
        time.sleep(2)


def _image_exists(tag: str = IMAGE_TAG) -> bool:
    """Check if a Docker image exists locally."""
    result = _run(["docker", "images", "-q", tag], check=False)
    return bool(result.stdout.strip())


def _ensure_docker_image() -> None:
    """Build the custom Nextcloud image if not already present."""
    if _image_exists():
        logger.info("Docker image %s already exists", IMAGE_TAG)
        return
    logger.info("Building Docker image %s...", IMAGE_TAG)
    _run(
        ["docker", "build", "--network", "host", "-t", IMAGE_TAG,
         str(BUILD_CONTEXT), "-f", str(DOCKERFILE_PATH)],
    )
    logger.info("Docker image %s built", IMAGE_TAG)


def _clean_nc_data() -> None:
    """Remove nc_data directory using Docker (files owned by www-data)."""
    if NCDATA_MOUNT_POINT.exists():
        parent = str(NCDATA_MOUNT_POINT.parent)
        name = NCDATA_MOUNT_POINT.name
        _run(
            ["docker", "run", "--rm", "-v", f"{parent}:/tmp/work", "alpine",
             "rm", "-rf", f"/tmp/work/{name}"],
            check=False,
        )
        if NCDATA_MOUNT_POINT.exists():
            shutil.rmtree(str(NCDATA_MOUNT_POINT), ignore_errors=True)
    NCDATA_MOUNT_POINT.mkdir(parents=True, exist_ok=True)
    logger.info("nc_data cleaned")


def _user_exists(username: str) -> bool:
    result = _occ(["user:list", "--output=json"], check=False)
    if result.returncode != 0:
        return False
    try:
        users = json.loads(result.stdout)
        return username in users
    except (json.JSONDecodeError, KeyError):
        return False


def _is_already_setup() -> bool:
    return _user_exists("testuser")


def start() -> None:
    _ensure_docker_image()
    _ensure_rclone_mount()

    if not NCDATA_MOUNT_POINT.exists() or not (NCDATA_MOUNT_POINT / "config" / "config.php").exists():
        _clean_nc_data()
        fresh = True
    else:
        fresh = False

    logger.info("Starting Nextcloud stack...")
    _run(["docker", "compose", "-f", str(COMPOSE_FILE), "up", "-d"])
    _wait_for_healthy()

    if not _container_mount_ok():
        raise RuntimeError("Nextcloud container cannot access /mnt/gdrive after restart")

    # Clear rate limiter for localhost — tests hit WebDAV rapidly
    for ip in ("127.0.0.1", "::1"):
        _occ(["security:bruteforce:reset", ip], check=False)

    # Disable password_policy so testpass123 is accepted (runs every time,
    # even on reuse, in case a partial setup left testuser in a bad state)
    _occ(["app:disable", "password_policy"], check=False)

    if _is_already_setup() and not fresh:
        logger.info("Nextcloud already configured, reusing existing data")
        return

    result = _occ(["app:enable", "files_external"])
    if result.returncode != 0:
        logger.error(_fail("Failed to enable files_external app", result))
        return
    logger.info("files_external app enabled")

    result = _run(
        ["docker", "exec", "-e", "OC_PASS=testpass123", NEXTCLOUD_CONTAINER]
        + OCC + ["user:add", "--password-from-env", "testuser"],
        check=False,
    )
    if result.returncode != 0:
        logger.error(_fail("Failed to create testuser", result))
        return
    if not _user_exists("testuser"):
        logger.error("testuser not found after successful user:add command")
        return
    logger.info("testuser created")

    for domain, value in [("0", "localhost"), ("1", "10.0.2.2")]:
        result = _occ(["config:system:set", "trusted_domains", domain, "--value", value])
        if result.returncode != 0:
            logger.error(_fail(f"Failed to set trusted_domain {domain}={value}", result))
            return
    logger.info("Trusted domains set: localhost, 10.0.2.2")

    _configure_local_mount()

    # Verify testuser can see PicPocketTest via WebDAV
    result = _run(
        ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
         "-u", "testuser:testpass123",
         "-X", "PROPFIND",
         "http://localhost:8080/remote.php/dav/files/testuser/PicPocketTest",
         "-H", "Depth: 0"],
        check=False,
    )
    if result.stdout.strip() == "207":
        logger.info("PicPocketTest accessible via WebDAV (HTTP 207)")
    else:
        logger.warning(
            "PicPocketTest WebDAV check returned HTTP %s (expected 207)",
            result.stdout.strip(),
        )

    logger.info("Nextcloud infrastructure ready")


def stop() -> None:
    result = _run(
        ["docker", "compose", "-f", str(COMPOSE_FILE), "down"],
        check=False,
    )
    if result.returncode != 0:
        logger.warning("docker compose down returned %d: %s", result.returncode, result.stderr.strip())
    else:
        logger.info("Nextcloud stack stopped")


def reset_bruteforce() -> None:
    """Reset Nextcloud rate limiter for localhost IPs."""
    for ip in ("127.0.0.1", "::1"):
        _occ(["security:bruteforce:reset", ip], check=False)
    logger.info("Bruteforce protection reset for localhost")


def reset_data() -> None:
    """Full reset — cleans all data for a clean test state."""
    result = _run(
        ["docker", "compose", "-f", str(COMPOSE_FILE), "down", "-v"],
        check=False,
    )
    if result.returncode != 0:
        logger.warning("docker compose down -v returned %d: %s", result.returncode, result.stderr.strip())
    _clean_nc_data()
    logger.info("Nextcloud data reset complete")


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    start()