import logging
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


@pytest.fixture(scope="session", autouse=True)
def nextcloud_infra():
    """Session-scoped Nextcloud infrastructure (start/teardown)."""
    nextcloud.start()
    yield
    nextcloud.empty_drive_trash()
    nextcloud.stop()


@pytest.fixture(scope="session")
def oracle():
    from oracle.nextcloud_oracle import NextcloudOracle
    return NextcloudOracle()


@pytest.fixture(scope="session")
def device_serials():
    from scripts.ensure_emulator import boot_from_snapshot
    serial = boot_from_snapshot()
    _disable_captive_portal(AdbDevice(serial))
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
    _disable_captive_portal(AdbDevice(serial))
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
def two_devices(serial_b):
    """Marker fixture: ensures device B is booted for tests that need it."""
    return serial_b


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


def _disable_captive_portal(device: AdbDevice):
    """Stop Android from periodically disabling the emulator WiFi.

    Android's captive-portal validation probes the internet and, when the
    probe times out (common on test hosts with limited internet), marks the
    virtual WiFi as "no internet access" and DISABLES it for a while. During
    that window every connection through the network — including the app's
    WebDAV calls to 10.0.2.2:8080 — is dropped at TCP connect, surfacing in
    the app as a 15s ConnectTimeout on the sync-lock PUT. Disabling the
    captive portal checks keeps the network stable.
    """
    for setting, value in [
        ("captive_portal_mode", "0"),
        ("captive_portal_detection_enabled", "0"),
        ("wifi_watchdog_on", "0"),
        ("wifi_watchdog_poor_network_test_enabled", "0"),
    ]:
        device.shell(f"settings put global {setting} {value}", timeout=10)
    device.shell("settings delete global captive_portal_server", timeout=10)
    logger.info("Captive portal detection disabled on %s", device.serial)


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
def reset_state(serial_a, oracle):
    """Reset device A and the shared Drive folder before a test.

    Deliberately does NOT touch device B: single-device tests must not drag
    a second emulator into the session. Two-device tests additionally request
    reset_state_b to reset B.
    """
    nextcloud.reset_bruteforce()
    adb = AdbDevice(serial_a)
    _ensure_apk(adb)
    _enable_tracing(adb)
    _ensure_local_folder(adb)
    _clear_sync_state(adb)
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
    logger.info("Test state reset complete")


@pytest.fixture
def reset_state_b(serial_b):
    """Reset device B only (used by two-device tests; boots B on demand)."""
    adb = AdbDevice(serial_b)
    _ensure_apk(adb)
    _enable_tracing(adb)
    _ensure_local_folder(adb)
    _clear_sync_state(adb)
    adb.shell("am force-stop com.google.android.documentsui || true")
    _verify_nextcloud_account(adb)
    logger.info("Device B state reset complete")


def _clear_sync_state(device: AdbDevice):
    """Remove persisted sync config so SAF folder selection runs fresh."""
    device.shell(
        f"run-as {APP_PACKAGE} rm -f files/drive_index.json "
        f"shared_prefs/sync_settings.xml "
        f"shared_prefs/sync_retry.xml "
        f"shared_prefs/sync_checkpoint.xml "
        f"files/sync_journal.json 2>/dev/null; "
        f"run-as {APP_PACKAGE} rm -rf files/documents 2>/dev/null; true"
    )
    logger.info("Sync state cleared on %s", device.serial)


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
    device.install_apk(APK_PATH)
    logger.info("APK installed on %s", device.serial)


@pytest.hookimpl(tryfirst=True, hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    rep = outcome.get_result()
    setattr(item, "rep_" + rep.when, rep)
