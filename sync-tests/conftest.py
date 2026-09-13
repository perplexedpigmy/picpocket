import logging
import os
import subprocess
import time
from pathlib import Path

import pytest

from devices.adb import AdbDevice
from devices.ui import UiDevice
from devices.tracing import SyncWatcher
from infra import nextcloud

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger(__name__)

_project_root = Path(__file__).resolve().parent.parent
APK_PATH = str(_project_root / "app/build/outputs/apk/debug/app-debug.apk")

_SERIALS = ("emulator-5554", "emulator-5556")


def _dump_failure_diagnostics() -> None:
    """Dump sync-relevant logcat + Nextcloud container logs on test failure.

    The sync failures are bridge-staleness races whose exact cause is only
    visible in the device's own SyncManager/DownloadEngine logcat and in the
    server's request logs (e.g. a WebDAV GET that 404s). The teardown clears
    logcat and the session teardown stops the container, so capture both at
    the moment the test fails.
    """
    for serial in _SERIALS:
        try:
            out = subprocess.run(
                ["adb", "-s", serial, "logcat", "-d"],
                capture_output=True, text=True, timeout=30,
            ).stdout or ""
        except Exception as e:  # noqa: BLE001 - diagnostic must never fail the run
            logger.warning("logcat dump %s failed: %s", serial, e)
            continue
        relevant = [
            l for l in out.splitlines()
            if any(t in l for t in (
                "SyncManager", "DownloadEngine", "DriveFileManager", "UploadEngine",
                "DeviceRegistry", "SAFProbe", "DocumentsStorageProvider",
                "ReadFolderRemoteOperation", "OwnCloudClient", "SynchronizeFileOperation",
                # import + store tags: an offline-sync failure often boils down
                # to whether the imported doc actually landed in the local store
                "HomeViewModel", "DocumentRepository", "DocumentStore",
                "PdfPageImporter", "ScannerViewModel", "importPdf",
            ))
        ]
        logger.info("===== %s logcat (%d relevant lines) =====", serial, len(relevant))
        for line in relevant[-120:]:
            logger.info("  %s", line)
    try:
        out = subprocess.run(
            ["docker", "logs", "--tail", "120", "sync-tests-nextcloud-1"],
            capture_output=True, text=True, timeout=30,
        ).stdout or ""
    except Exception as e:  # noqa: BLE001
        logger.warning("nextcloud container log dump failed: %s", e)
        return
    logger.info("===== nextcloud container logs (tail 120) =====")
    for line in out.splitlines()[-120:]:
        logger.info("  %s", line)


@pytest.fixture(scope="session", autouse=True)
def nextcloud_infra():
    """Session-scoped Nextcloud infrastructure (start/teardown).

    start() is idempotent on a warm stack, and the bring-up is serialized with
    a file lock (_start_stack in infra/nextcloud.py): docker compose up -d races
    between parallel workers on a cold start and one exits non-zero. Teardown
    skips docker compose down for a parallel worker (NEXTCLOUD_SUBDIR set): the
    stack is shared, and a worker finishing first must not tear it down under a
    sibling; the serial full-gate session owns the stop.
    """
    nextcloud.start()
    yield
    nextcloud.empty_drive_trash()
    if not os.environ.get("NEXTCLOUD_SUBDIR", "").strip():
        nextcloud.stop()


@pytest.fixture(scope="session")
def oracle():
    from oracle.nextcloud_oracle import NextcloudOracle
    return NextcloudOracle(subdir=os.environ.get("NEXTCLOUD_SUBDIR", ""))


@pytest.fixture(scope="session")
def device_serials():
    from scripts.ensure_emulator import boot_from_snapshot
    serial = boot_from_snapshot()
    # The emulator boots fresh from its snapshot, so any APK-install markers a
    # previous session left are stale (the reboot reverted the app to the
    # snapshot build). Clear them so this session installs the current APK
    # exactly once per device.
    for marker in _APK_HASH_FILE.parent.glob(".apk_hash_*"):
        marker.unlink()
    _stabilize_network(AdbDevice(serial))
    _disable_play_updates(AdbDevice(serial))
    return [serial]


@pytest.fixture
def serial_a(device_serials):
    return device_serials[0]


@pytest.fixture(scope="session")
def serial_b():
    """Serial for device B, booted lazily on the first request.

    Only tests that actually use device B pull this fixture, so single-device
    tests never boot (or reset) a second emulator. Booted once per session.
    """
    from scripts.ensure_emulator import boot_device_b
    serial = boot_device_b()
    _stabilize_network(AdbDevice(serial))
    _disable_play_updates(AdbDevice(serial))
    return serial


@pytest.fixture
def emu_a(serial_a, request):
    adb = AdbDevice(serial_a)
    _ensure_apk(adb)
    yield UiDevice(serial_a)


@pytest.fixture
def emu_b(serial_b, request):
    adb = AdbDevice(serial_b)
    _ensure_apk(adb)
    yield UiDevice(serial_b)


@pytest.fixture
def two_devices(request, serial_b):
    """Marker fixture: ensures device B is booted for tests that need it.

    Forces the shared PicPocketTest root for the duration of the test so
    w1/w2 isolation cannot split the lock. Single-device parallel (w1/w2)
    stays isolated; two-device always uses the shared repo.
    """
    old = os.environ.pop("NEXTCLOUD_SUBDIR", None)
    old_drive = os.environ.pop("DRIVE_SUBDIR", None)
    try:
        yield serial_b
    finally:
        if old is not None:
            os.environ["NEXTCLOUD_SUBDIR"] = old
        if old_drive is not None:
            os.environ["DRIVE_SUBDIR"] = old_drive


@pytest.fixture
def watcher_a(serial_a):
    return SyncWatcher(AdbDevice(serial_a))


@pytest.fixture
def watcher_b(serial_b):
    return SyncWatcher(AdbDevice(serial_b))


@pytest.fixture
def ensure_drive_configured(emu_a, reset_state):
    emu_a.ensure_drive_configured()


NEXTCLOUD_APP_PACKAGE = "com.nextcloud.client"
APP_PACKAGE = "com.picpocket.app"
NEXTCLOUD_USER = "testuser"
NEXTCLOUD_PASS = "testpass123"

_APK_HASH_FILE = Path(__file__).resolve().parent / "tmp" / ".apk_hash"


def _apk_hash_file(device: AdbDevice) -> Path:
    """Per-device APK hash marker.

    The hash is per-serial because each emulator needs its own install: if A
    installs the APK and writes a single global marker, device B would then
    skip its own (required) install. Storing one marker per serial keeps the
    install skipped only for devices that already got this exact APK.
    """
    name = f".apk_hash_{device.serial.replace(':', '_')}"
    return _APK_HASH_FILE.parent / name


def _apk_hash() -> str:
    """Stable hash of the current debug APK; used to skip reinstalling when the
    APK hasn't changed since the last install.

    The APK is ~90 MB, so each `adb install -r` costs several seconds and runs
    twice per test (reset_state + emu_a). Within a session the APK is unchanged,
    so caching it removes that overhead without changing test behavior.
    """
    try:
        import hashlib

        return hashlib.sha256(Path(APK_PATH).read_bytes()).hexdigest()[:16]
    except OSError:
        return ""


def _mark_apk_installed(device: AdbDevice):
    marker = _apk_hash_file(device)
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text(_apk_hash())


def pytest_configure(config):
    config.addinivalue_line(
        "markers",
        "drive_finality: assert full Google Drive MD5/non-partial finality "
        "(slower; only run on the few tests that need it)",
    )


def _enable_tracing(device: AdbDevice):
    """Enable Tracing in PicPocket via run-as."""
    tracing_xml = f"/data/data/{APP_PACKAGE}/shared_prefs/tracing.xml"
    xml_content = (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
        "<map>"
        "<boolean name='tracing_enabled' value='true'/>"
        "<int name='tracing_override_DRIVE_API' value='1'/>"
        "<int name='tracing_override_DRIVE_FILES' value='1'/>"
        "<int name='tracing_override_STORE_STATE' value='1'/>"
        "</map>"
    )
    device.shell(f'echo "{xml_content}" > /data/local/tmp/tracing.xml')
    device.shell(
        f"run-as {APP_PACKAGE} mkdir -p shared_prefs files",
        timeout=10,
    )
    device.shell(
        f"run-as {APP_PACKAGE} sh -c 'cat /data/local/tmp/tracing.xml > {tracing_xml}'",
        timeout=10,
    )
    time.sleep(1)
    verify = device.shell(
        f"run-as {APP_PACKAGE} cat {tracing_xml} 2>/dev/null || true", timeout=10,
    )
    if "tracing_enabled" in verify:
        logger.info("Tracing enabled on %s", device.serial)
    else:
        logger.error("Failed to enable Tracing on %s: %s", device.serial, verify.strip() or "empty")


def _stabilize_network(device: AdbDevice):
    """Make the emulator's network reliable for sync tests.

    The virtio virtual WiFi ("AndroidWifi") is flaky: wpa_supplicant reports
    beacon loss constantly and, when Android's internet-validation probe fails
    (DNS to Google is blocked on the test host), ConnectivityService tears the
    WiFi down and fails over to cellular, killing the app's in-flight WebDAV
    connections. The Nextcloud client then sits on its 60s socket read timeout
    and the sync aborts.

    Instead we ride the emulated cellular path (eth0 — a plain virtual
    ethernet NIC with no beacon logic) and disable network validation so no
    network is ever torn down or failed over. The account and the app talk to
    10.0.2.2:8080, which is reachable over eth0 identically.
    """
    for setting, value in [
        ("captive_portal_mode", "0"),
        ("captive_portal_detection_enabled", "0"),
        ("wifi_watchdog_on", "0"),
        ("wifi_watchdog_poor_network_test_enabled", "0"),
        ("network_validation_enabled", "0"),
        ("no_internet_expectation", "1"),
    ]:
        device.shell(f"settings put global {setting} {value}", timeout=10)
    device.shell("settings delete global captive_portal_server", timeout=10)
    # Switch to the stable ethernet-like data path (eth0) before tests run.
    device.shell("svc data enable", timeout=15)
    device.shell("svc wifi disable", timeout=15)
    time.sleep(8)
    logger.info("Network stabilized on %s (wifi off, eth0/cellular default)", device.serial)


def _disable_play_updates(device: AdbDevice):
    """Stop Google Play from pushing background updates on the test emulator.

    Finsky (Play Store) kicks off large GMS/Play package downloads in the
    background. Mid-test that floods logcat — rotating out the app's tracing
    lines the SyncWatcher needs — and competes for the emulated network,
    aggravating the intermittent drops. The tests don't use Play Store, so
    disable it on boot.
    """
    device.shell("pm disable-user --user 0 com.android.vending 2>/dev/null || true", timeout=10)
    logger.info("Play Store disabled on %s", device.serial)


def _account_exists(device: AdbDevice) -> bool:
    """Check if a Nextcloud account exists on the device."""
    out = device.shell("dumpsys account 2>/dev/null | grep 'type=nextcloud' || true")
    return "nextcloud" in out


def _verify_nextcloud_account(device: AdbDevice):
    """Verify the snapshot-baked Nextcloud account is present.

    The sync_test_ready snapshot bakes in a working Nextcloud account. The
    SAF DocumentsProvider uses it as-is, so it must NOT be cleared or
    re-created here — pm clear + deep-link re-auth was found to break the
    provider (empty root listing). The account is only validated, never
    modified.
    """
    if _account_exists(device):
        logger.info("Nextcloud account present on %s (snapshot, no re-auth)", device.serial)
    else:
        logger.error("Nextcloud account NOT found on %s — snapshot may be stale", device.serial)


@pytest.fixture
def fresh_sync_config(serial_a):
    """Wipe the device's folder selection + sync settings so SAF folder
    selection runs fresh.

    Opt-in for tests that exercise folder selection. The default reset keeps
    sync configured across tests so the slow SAF picker is skipped.
    """
    _clear_sync_state(AdbDevice(serial_a), keep_config=False)


@pytest.fixture
def reset_state(request, serial_a, oracle):
    """Reset device A and the shared Drive folder before and after a test.

    Setup wipes the worker-scoped Drive folder (w1/w2 for single-device
    parallel, shared PicPocketTest for >=2 devices) so each test starts with
    nothing on the remote. Cleanup wipes again after the test so the remote
    is left empty even if the next test never runs.

    Deliberately does NOT touch device B: single-device tests must not drag
    a second emulator into the session. Two-device tests additionally request
    reset_state_b to reset B.

    For >=2 devices the SAF folder selection (drive_index.json) is wiped so
    both devices re-pick the shared PicPocketTest root. Single-device keeps
    the selection to skip the slow picker.
    """
    is_two = "two_devices" in request.fixturenames or "reset_state_b" in request.fixturenames

    def _setup():
        nextcloud.reset_bruteforce()
        adb = AdbDevice(serial_a)
        _ensure_apk(adb)
        _enable_tracing(adb)
        adb.logcat_clear()
        _ensure_local_folder(adb)
        _clear_sync_state(adb, keep_config=not is_two)
        adb.shell("am force-stop com.google.android.documentsui || true")
        _verify_nextcloud_account(adb)
        nextcloud._occ(["files:scan", "--all"], check=False)
        try:
            oracle.clear_all()
            logger.info("Drive test folder cleared")
        except Exception as e:
            logger.warning("Failed to clear Drive: %s", e)
        nextcloud.purge_drive()
        time.sleep(2)
        logger.info("Test state reset complete (is_two=%s)", is_two)

    def _teardown():
        # Retry Drive wipe — a prior test that failed with LOCKED (423) can
        # leave a file locked; clear_all retries 3× but may still leave it.
        # Poll until the worker-scoped folder is empty and devices.json is
        # readable, so the next test does not see "devices.json … could not
        # be read".
        for attempt in range(3):
            try:
                oracle.clear_all()
                logger.info("Drive test folder cleaned up (attempt %d)", attempt + 1)
            except Exception as e:
                logger.warning("Failed to clean Drive after test (attempt %d): %s", attempt + 1, e)
            try:
                nextcloud.purge_drive()
            except Exception as e:
                logger.warning("purge_drive after test failed (attempt %d): %s", attempt + 1, e)
            # Verify the folder is empty and devices.json is not half-written
            try:
                remaining = oracle._list_entries("/PicPocketTest")
                if not remaining:
                    # Also verify devices.json is gone (404) or readable JSON —
                    # the App's next syncRegistryFromDrive does openInputStream
                    # and fails with "exists but could not be read" if the
                    # file is listed but 0 bytes.
                    try:
                        oracle.get_file_content("/PicPocketTest/devices.json")
                        logger.warning("devices.json still present after cleanup, retrying")
                    except Exception as ex:
                        # 404 is expected for empty folder; anything else retry
                        if "404" in str(ex):
                            break
                        logger.warning("devices.json not readable after cleanup: %s", ex)
                    else:
                        # Got content but folder should be empty — retry
                        time.sleep(2)
                        continue
                    break
                logger.warning("Drive not empty after cleanup %s, retrying", remaining)
            except Exception as e:
                logger.warning("Failed to list Drive after cleanup (attempt %d): %s", attempt + 1, e)
            time.sleep(10)
        # Always wipe local documents and retry state; keep folder selection
        # only for single-device (so next single-device test can skip picker).
        try:
            _clear_sync_state(AdbDevice(serial_a), keep_config=not is_two)
        except Exception as e:
            logger.warning("Failed to clear sync state after test: %s", e)

    _setup()
    yield
    _teardown()


@pytest.fixture
def reset_state_b(request, serial_b):
    """Reset device B only (used by two-device tests; boots B on demand)."""
    is_two = True  # reset_state_b is only requested by two-device tests

    def _setup():
        adb = AdbDevice(serial_b)
        _ensure_apk(adb)
        _enable_tracing(adb)
        adb.logcat_clear()
        _ensure_local_folder(adb)
        _clear_sync_state(adb, keep_config=False)
        adb.shell("am force-stop com.google.android.documentsui || true")
        _verify_nextcloud_account(adb)
        logger.info("Device B state reset complete")

    def _teardown():
        try:
            _clear_sync_state(AdbDevice(serial_b), keep_config=False)
        except Exception as e:
            logger.warning("Failed to clear sync state B after test: %s", e)
        try:
            AdbDevice(serial_b).shell("rm -rf /sdcard/PicPocketTest && mkdir -p /sdcard/PicPocketTest")
        except Exception:
            pass

    _setup()
    yield
    _teardown()


def _clear_sync_state(device: AdbDevice, keep_config: bool = True):
    """Reset per-test app state.

    By default KEEPS the folder selection (drive_index.json) and sync settings
    (sync_settings.xml) so the app does not re-run the slow Nextcloud SAF folder
    picker on every test. Stale retry/checkpoint/journal artifacts, the
    encryption passphrase store, and downloaded documents are still removed.
    Pass keep_config=False (via the fresh_sync_config fixture) for tests that
    exercise folder selection itself.

    Force-stop happens FIRST on every reset, not just config wipes: app
    singletons (EncryptionManager, LocalDriveIndex, the PassphraseStore prefs)
    cache state in memory loaded once at process start, so a still-running app
    from a previous test keeps stale state even though its config files were
    kept, and file deletions only take effect once the process restarts.
    """
    device.shell(f"am force-stop {APP_PACKAGE} || true")
    state_files = (
        "files/sync_retry.xml "
        "shared_prefs/sync_checkpoint.xml "
        "files/sync_journal.json"
    )
    if not keep_config:
        state_files += " files/drive_index.json shared_prefs/sync_settings.xml"
    # The encryption passphrase is persisted in an EncryptedSharedPreferences
    # file and auto-restored at process start (SyncViewModel.init), so a test
    # that enables encryption (test_04) would leak encrypted sync into every
    # later test no matter how often the app is force-stopped. Remove the store
    # so each test starts unencrypted unless it sets a passphrase itself.
    state_files += " shared_prefs/drive_passphrase_prefs.xml"
    device.shell(
        f"run-as {APP_PACKAGE} rm -f {state_files} 2>/dev/null; "
        f"run-as {APP_PACKAGE} rm -rf files/documents 2>/dev/null; true"
    )
    if keep_config:
        # drive_index.json is kept (it holds the folder selection); reset its
        # passphrase generation so a cleared passphrase can't leave a stale
        # count behind in the kept index.
        device.shell(
            f"run-as {APP_PACKAGE} sed -i 's/\"passphraseCount\": [0-9]*/\"passphraseCount\": 0/' "
            f"files/drive_index.json 2>/dev/null || true"
        )
    logger.info("Sync state cleared on %s (keep_config=%s)", device.serial, keep_config)


def _ensure_local_folder(device: AdbDevice):
    device.shell("rm -rf /sdcard/PicPocketTest && mkdir -p /sdcard/PicPocketTest")
    logger.info("Local PicPocketTest dir ensured on %s", device.serial)


def _ensure_apk(device: AdbDevice):
    if not Path(APK_PATH).exists():
        logger.info("APK missing, building with gradle...")
        _project_root.mkdir(exist_ok=True)
        result = subprocess.run(
            [str(_project_root / "gradlew"), "assembleDebug"],
            cwd=str(_project_root),
            capture_output=True,
            text=True,
            timeout=600,
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"gradle assembleDebug failed:\n{result.stdout}\n{result.stderr}"
            )
        logger.info("APK built at %s", APK_PATH)
    # Skip the (expensive, ~90 MB) reinstall when the APK is unchanged since
    # the last install on THIS device. The marker files live in tmp/, which is
    # git-ignored, so the cache is per-session/per-machine only.
    if _apk_hash_file(device).exists() and _apk_hash_file(device).read_text().strip() == _apk_hash():
        logger.info("APK unchanged on %s — skipping reinstall", device.serial)
        return
    device.install_apk(APK_PATH)
    _mark_apk_installed(device)
    logger.info("APK installed on %s", device.serial)


@pytest.hookimpl(tryfirst=True, hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    rep = outcome.get_result()
    setattr(item, "rep_" + rep.when, rep)
    if rep.when == "call" and rep.failed:
        _dump_failure_diagnostics()
