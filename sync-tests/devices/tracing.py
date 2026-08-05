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
LOCK_ENOENT = re.compile(r"performSync:\s*error open failed: ENOENT")
DOWNLOAD_ERR = re.compile(r"performSync:\s*error Error downloading file:")
UPLOAD_ABORT = re.compile(r"performSync:\s*upload.*(?:failed|threw) for doc=")
WRONG_PASSPHRASE = re.compile(r"performSync:\s*wrong passphrase")
ENCRYPTION_GATING = re.compile(r"Drive encrypted, passphrase required")
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
        # The logcat wait already covered the full timeout. The index fallback
        # is just a verification that the registry moved, so keep it short;
        # otherwise a failed sync burns the timeout twice (e.g. 150s + 150s).
        result = self._wait_index(since, min(15.0, timeout))
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

    def wait_for_client_refresh(self, doc_id: str, timeout: float = 30.0) -> bool:
        """Wait until the Nextcloud client saved a refreshed listing for a doc folder.

        The bridge (Nextcloud client DocumentsProvider) serves folder listings
        from its local DB, which lags out-of-band server writes by one refresh
        cycle. A PicPocket sync accesses the folder, which triggers the client's
        on-demand RefreshFolderOperation; this waits for it to persist the new
        children so the next sync sees them.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            output = self.device.logcat()
            if output:
                for line in output.splitlines():
                    if "Saving folder" in line and doc_id in line:
                        logger.info("Client refreshed doc folder: %s", line.strip())
                        return True
            time.sleep(1)
        logger.warning("Client did not refresh doc folder %s within %.0fs", doc_id, timeout)
        return False

    def wait_for_pattern(self, pattern: re.Pattern, timeout: float = 60.0) -> str:
        deadline = time.time() + timeout
        last_lines = []
        while time.time() < deadline:
            output = self.device.logcat()
            if output:
                lines = output.splitlines()
                last_lines = lines[-15:]
                # Scan the whole buffer (not just the tail): the event we are
                # waiting for may already have been emitted during trigger_sync
                # before this watcher started, and logcat_clear() used to wipe it.
                for line in lines:
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

    def had_false_mutex_abort(self) -> bool:
        """True if the most recent sync aborted spuriously on the lock file.

        The root sync-lock.json sits behind the same laggy Nextcloud bridge
        that delays folder listings, so the mutex acquire can fail transiently
        in two ways, both with no other device holding the lock:

        - SyncMutex.acquire() returns false ("mutex locked by another
          device"): the lock-file createFile PUT failed and androidx
          TreeDocumentFile turned the FileNotFoundException into null.
        - acquire() throws "open failed: ENOENT": the lock file was written
          but readLockData's openInputStream can't see it yet.
        - acquire() fails to download the lock file ("Error downloading file:
          sync-lock.json"): the emulator's virtual WiFi intermittently drops
          all egress connections for ~1s (wpa_supplicant BEACON-LOSS), and the
          client reports HTTP -1 / UNKNOWN_ERROR with a null exception. The
          same drop can hit any other client download mid-sync (metadata.json,
          page files), aborting with the identical message.
        - a doc upload aborts ("upload failed for doc=... / aborting sync"):
          the same radio drop can kill the client's PUT after its ~60s socket
          timeout, aborting the whole sync rather than just the mutex step.
        - a "wrong passphrase" abort fires spuriously: the metadata.json read
          can fail transiently (bridge cache lag / radio drop), yielding null
          metadata and anyDecrypted=false, which the app mislabels as a wrong
          passphrase. The next sync with the same passphrase succeeds.

        Each failed attempt triggers the client's on-demand refresh, so
        retrying the sync right after usually succeeds.
        """
        output = self.device.logcat() or ""
        lines = [l for l in output.splitlines() if "performSync" in l]
        if not lines:
            return False
        last = lines[-1]
        return (
            MUTEX_LOCKED.search(last) is not None
            or LOCK_ENOENT.search(last) is not None
            or DOWNLOAD_ERR.search(last) is not None
            or UPLOAD_ABORT.search(last) is not None
            or WRONG_PASSPHRASE.search(last) is not None
        )

    def had_sync_never_started(self) -> bool:
        """True if the last wait window produced no performSync line at all.

        After a wait that clears logcat, a total absence of performSync lines
        means the "Sync Now" tap did not actually start a sync (uiautomator
        tap flakiness), rather than the sync running and failing.
        """
        output = self.device.logcat() or ""
        return not any("performSync" in line for line in output.splitlines())

    def had_sync_in_progress(self) -> bool:
        """True if a performSync is currently running but not yet done.

        The most recent performSync line is a progress line (e.g. gating,
        uploading) rather than a terminal one (complete / error / mutex or
        lock abort / upload abort). Used to keep waiting on an app-initiated
        sync (e.g. the one launched by setEncryptionPassphrase) without
        re-triggering, since navigating away would cancel it via
        viewModelScope teardown.
        """
        output = self.device.logcat() or ""
        lines = [l for l in output.splitlines() if "performSync" in l]
        if not lines:
            return False
        last = lines[-1]
        terminal = (
            SYNC_COMPLETE,
            SYNC_ERROR,
            MUTEX_LOCKED,
            LOCK_ENOENT,
            DOWNLOAD_ERR,
            UPLOAD_ABORT,
            WRONG_PASSPHRASE,
        )
        return not any(p.search(last) for p in terminal)

    def sync_config_ok(self) -> bool:
        """True if the app still has sync enabled and a folder configured.

        Guards the "sync never started" retry so we don't spin uselessly when
        the app is in a genuinely unrecoverable state (sync disabled, no
        folder), which retrying the tap cannot fix.
        """
        try:
            prefs = self.device.shell(
                f"run-as {APP_PACKAGE} cat shared_prefs/sync_settings.xml 2>/dev/null || true",
                timeout=5,
            )
            index = self.device.shell(
                f"run-as {APP_PACKAGE} cat files/drive_index.json 2>/dev/null || true",
                timeout=5,
            )
        except Exception:  # noqa: BLE001 - treat probe failure as not-ok
            return False
        enabled = "sync_enabled" in (prefs or "") and 'value="true"' in (prefs or "")
        configured = '"rootTreeUri"' in (index or "") and '"localDeviceId"' in (index or "")
        return enabled and configured


def _should_retry_sync(watcher: SyncWatcher) -> bool:
    """Whether an incomplete sync is worth a re-trigger.

    Retries when the sync either aborted spuriously on the lock file (bridge
    lag / server slowness) or never started at all (the "Sync Now" adb tap
    missed). For the never-started case, require the app to still be in a
    syncing-ready state so we don't spin on a real config problem.
    """
    if watcher.had_false_mutex_abort():
        return True
    if watcher.had_sync_never_started():
        return watcher.sync_config_ok()
    return False


def sync_with_false_mutex_retry(emu, watcher, timeout: float = 60.0,
                                retries: int = 5, settle: float = 12.0,
                                trigger: bool = True) -> str:
    """Trigger a sync and wait, retrying when it aborts spuriously.

    The root lock file (sync-lock.json) lives behind the same laggy Nextcloud
    bridge that delays folder listings, so the app's mutex acquire can fail
    transiently with either "mutex locked by another device" (createFile null)
    or "error open failed: ENOENT" (read-back invisible) even though nothing
    else holds the lock. Each failed attempt triggers the client's on-demand
    root refresh, so a retry after a short settle succeeds.

    With trigger=False the first attempt does NOT tap "Sync Now": it waits for
    a sync the app already started on its own (setEncryptionPassphrase
    launches one in the SyncViewModel's viewModelScope). Triggering another
    one would navigate home and pop the Sync screen, destroying the ViewModel
    and cancelling the in-flight sync ("Job was cancelled"). Subsequent
    retries still trigger manually once the auto-sync has settled.
    """
    for attempt in range(1, retries + 1):
        if trigger or attempt > 1:
            emu.trigger_sync()
        try:
            return watcher.wait_for_sync(timeout=timeout)
        except TimeoutError as exc:
            if _should_retry_sync(watcher):
                if attempt >= retries:
                    raise TimeoutError(
                        f"Sync did not complete after {retries} attempts: {exc}"
                    ) from exc
                logger.warning(
                    "Sync incomplete (spurious lock abort or missed tap, attempt %d/%d); settling %.0fs and retrying",
                    attempt, retries, settle,
                )
                time.sleep(settle)
                continue
            if not trigger and watcher.had_sync_in_progress():
                if attempt >= retries:
                    raise TimeoutError(
                        f"App-initiated sync still not complete after {retries} waits: {exc}"
                    ) from exc
                logger.warning(
                    "App-initiated sync still in progress (attempt %d/%d); settling %.0fs and waiting more",
                    attempt, retries, settle,
                )
                time.sleep(settle)
                continue
            raise
    raise TimeoutError("unreachable")


def wait_for_pattern_with_false_mutex_retry(emu, watcher, pattern: re.Pattern,
                                            timeout: float = 60.0,
                                            retries: int = 5,
                                            settle: float = 12.0) -> str:
    """Trigger a sync and wait for a logcat pattern, retrying on spurious abort.

    Variant of sync_with_false_mutex_retry for syncs whose expected outcome is
    NOT "performSync: complete" (e.g. a gating error like encryption-blocked):
    the wait succeeds on `pattern` instead. Still retries when the sync aborts
    spuriously on the lock file so a transient bridge hiccup can't mask the
    expected result.
    """
    for attempt in range(1, retries + 1):
        emu.trigger_sync()
        try:
            return watcher.wait_for_pattern(pattern, timeout=timeout)
        except TimeoutError as exc:
            if not _should_retry_sync(watcher):
                raise
            if attempt >= retries:
                raise TimeoutError(
                    f"Expected pattern {pattern.pattern} not seen after {retries} attempts: {exc}"
                ) from exc
            logger.warning(
                "Pattern %s not seen (spurious lock abort or missed tap, attempt %d/%d); settling %.0fs and retrying",
                pattern.pattern, attempt, retries, settle,
            )
            time.sleep(settle)
    raise TimeoutError("unreachable")
