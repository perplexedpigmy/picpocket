import json
import logging
import re
import time
from typing import Optional

from .adb import AdbDevice

logger = logging.getLogger(__name__)

SYNC_COMPLETE = re.compile(r"performSync:\s*complete")
SYNC_STARTED = re.compile(r"performSync:\s*starting")
SYNC_ERROR = re.compile(r"performSync:\s*error")
UPLOAD_DOC = re.compile(r"uploadDocument")
DOWNLOAD_DOC = re.compile(r"downloadFullDocument")
MUTEX_LOCKED = re.compile(r"mutex locked by another device")
ENCRYPTION_GATING = re.compile(r"remoteEncrypted=true, passphraseSet=false")
CHECK_FILES = re.compile(r"checkFiles")
CONFLICT = re.compile(r"detectConflicts")

APP_PACKAGE = "com.picpocket.app"


def _get_last_seen(device: AdbDevice) -> Optional[int]:
    raw = device.shell(
        f"run-as {APP_PACKAGE} cat files/drive_index.json 2>/dev/null || true",
        timeout=5,
    )
    if not raw:
        return None
    try:
        data = json.loads(raw)
        return data.get("devices", {}).get(
            data.get("localDeviceId", ""), {}
        ).get("lastSeen")
    except (json.JSONDecodeError, KeyError):
        return None


class SyncWatcher:
    def __init__(self, device: AdbDevice):
        self.device = device

    def _wait_logcat(self, pattern: re.Pattern, timeout: float) -> Optional[str]:
        self.device.logcat_clear()
        deadline = time.time() + timeout
        while time.time() < deadline:
            output = self.device.logcat()
            if output:
                for line in output.splitlines():
                    if pattern.search(line):
                        return line
            time.sleep(1)
        return None

    def _wait_index(self, since: int, timeout: float) -> Optional[int]:
        deadline = time.time() + timeout
        while time.time() < deadline:
            last_seen = _get_last_seen(self.device)
            if last_seen is not None and last_seen > since + 1000:
                return last_seen
            time.sleep(2)
        return None

    def wait_for_sync(self, timeout: float = 60.0) -> str:
        since = _get_last_seen(self.device) or 0
        line = self._wait_logcat(SYNC_COMPLETE, timeout)
        if line:
            return line
        result = self._wait_index(since, timeout)
        if result is not None:
            return f"sync complete (index lastSeen: {result})"
        full_log = self.device.logcat()
        sync_lines = [l for l in full_log.splitlines() if "performSync" in l]
        raise TimeoutError(
            f"Sync not detected within {timeout}s "
            f"(no logcat pattern, no index update from {since}).\n"
            f"performSync logcat lines:\n" + "\n".join(sync_lines[-30:])
        )

    def wait_for_start(self, timeout: float = 30.0) -> str:
        return self.wait_for_pattern(SYNC_STARTED, timeout)

    def wait_for_pattern(self, pattern: re.Pattern, timeout: float = 60.0) -> str:
        self.device.logcat_clear()
        deadline = time.time() + timeout
        last_lines = []
        while time.time() < deadline:
            output = self.device.logcat()
            if output:
                last_lines = output.splitlines()[-10:]
                for line in last_lines:
                    if pattern.search(line):
                        logger.info("Pattern matched: %s", line)
                        return line
            time.sleep(2)
        full_log = self.device.logcat()
        sync_lines = [l for l in full_log.splitlines() if "performSync" in l or "syncDisabled" in l or "syncEnabled" in l]
        raise TimeoutError(
            f"Pattern {pattern.pattern} not found within {timeout}s.\n"
            f"Last logcat lines:\n" + "\n".join(last_lines) + "\n"
            f"Sync-related logcat lines:\n" + "\n".join(sync_lines[-20:])
        )
