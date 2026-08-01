import logging
import time

import pytest

from devices.pdf_utils import generate_and_push
from infra.nextcloud import purge_drive, wait_drive_finalized

logger = logging.getLogger(__name__)

def _ensure_picpockettest_visible():
    """Write a non-hidden marker file via WebDAV so PicPocketTest is indexed in Nextcloud immediately."""
    from oracle.nextcloud_oracle import NextcloudOracle
    oracle = NextcloudOracle()
    oracle.write_file("marker.txt", "marker")
    oracle.write_file(".saf-marker", "marker")
    logger.info("PicPocketTest seeded with marker.txt via WebDAV")


def _adb_tap(text, emu_a, timeout=10):
    """Like _input_tap but uses adb shell input tap (even more reliable)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        el = emu_a.d(text=text)
        if el.exists:
            info = emu_a.d.jsonrpc.objInfo(el.selector)
            b = info["bounds"]
            cx = (b["left"] + b["right"]) // 2
            cy = (b["top"] + b["bottom"]) // 2
            emu_a.adb.shell(f"input tap {cx} {cy}")
            time.sleep(0.5)
            return True
        time.sleep(0.5)
    return False


class TestSafToDrive:
    @pytest.fixture(autouse=True)
    def setup(self, reset_state, emu_a, oracle):
        self.emu_a = emu_a
        yield
        try:
            oracle.clear_all()
            logger.info("Teardown: cleared remote PicPocketTest after test")
        except Exception as e:
            logger.warning("Teardown clear failed: %s", e)
        purge_drive()

    def test_select_picpockettest_folder(self, emu_a):
        _ensure_picpockettest_visible()
        self._select_picpockettest_folder(emu_a)

        toggle = emu_a.d(description="Toggle sync")
        assert toggle.wait(timeout=10), "Sync toggle not visible — folder selection may have failed"

    def test_small_file_through_chain(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "saf-test", pages=1)
        _ensure_picpockettest_visible()
        self._select_picpockettest_folder(emu_a)
        self._toggle_sync_on(emu_a)

        emu_a.import_pdf("saf-test.pdf")
        time.sleep(3)

        self._trigger_sync(emu_a)
        watcher_a.wait_for_sync()

        self._assert_verified_completion(oracle)

    def test_large_file_through_chain(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "saf-large", pages=50)
        _ensure_picpockettest_visible()
        self._select_picpockettest_folder(emu_a, timeout=90)
        self._toggle_sync_on(emu_a)

        emu_a.import_pdf("saf-large.pdf")
        time.sleep(3)

        self._trigger_sync(emu_a)
        watcher_a.wait_for_sync(timeout=300)

        self._assert_verified_completion(oracle, drive_timeout=300)

    def test_after_reinstall(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "saf-reinstall", pages=1)
        _ensure_picpockettest_visible()
        self._select_picpockettest_folder(emu_a)
        self._toggle_sync_on(emu_a)

        emu_a.import_pdf("saf-reinstall.pdf")
        time.sleep(3)

        self._trigger_sync(emu_a)
        watcher_a.wait_for_sync()

        self._assert_verified_completion(oracle)

    def _assert_verified_completion(self, oracle, drive_timeout=180):
        """Assert the sync landed end-to-end with verified completion.

        B1: every file under each synced doc folder is fully written on the
        WebDAV side (non-zero content length).
        B2: the Drive copy is finalized — no 'application/x-partial-download'
        residue, every file has a size and a real MD5.
        """
        entries = oracle._list_entries("/PicPocketTest")
        doc_folders = [name for name, is_col in entries if is_col and not name.startswith(".")]
        logger.info("Drive folder after sync: %s", entries)
        assert doc_folders, "No doc folders found on Drive after sync"

        for folder in doc_folders:
            for child, is_col in oracle._list_entries(f"/PicPocketTest/{folder}"):
                if is_col:
                    continue
                assert oracle.verify_file_complete(f"{folder}/{child}", expected_bytes=1, timeout=60), (
                    f"WebDAV file not complete: {folder}/{child}"
                )

        finalized = wait_drive_finalized("", min_size=0, timeout=drive_timeout)
        assert finalized, "Drive folder empty after sync"
        partial = [r["Path"] for r in finalized if r.get("MimeType") == "application/x-partial-download"]
        assert not partial, f"Partial-download residue on Drive: {partial}"
        no_hash = [r["Path"] for r in finalized if not (r.get("Hashes") or {}).get("md5")]
        assert not no_hash, f"Files without MD5 on Drive: {no_hash}"
        paths = [r["Path"] for r in finalized]
        dups = sorted({p for p in paths if paths.count(p) > 1})
        assert not dups, f"Duplicate file objects on Drive: {dups}"
        logger.info("Drive finality verified: %d file(s), no partial-download residue", len(finalized))

    def _select_picpockettest_folder(self, emu_a, timeout=60):
        emu_a.open_app()
        emu_a.open_settings()
        emu_a.d(text="Sync").click()
        time.sleep(1)
        # If sync is already configured (Select Folder button missing), go back
        connect_btn = emu_a.d(text="Select Folder")
        if not connect_btn.wait(timeout=5.0):
            logger.info("Sync already configured on %s — skipping folder selection", emu_a.serial)
            emu_a.d.press("back")
            return
        time.sleep(1)
        connect_btn = emu_a.d(text="Select Folder")
        assert connect_btn.wait(timeout=10), "Select Folder button not found"
        connect_btn.click()
        time.sleep(3)

        emu_a.select_saf_folder("PicPocketTest", timeout)

        allow_btn = emu_a.d(text="ALLOW")
        if allow_btn.wait(timeout=5):
            allow_btn.click()
            time.sleep(2)
        logger.info("SAF: PicPocketTest selected on %s", emu_a.serial)

    def _toggle_sync_on(self, emu_a):
        toggle_elem = emu_a.d(description="Toggle sync")
        if toggle_elem.wait(timeout=5):
            toggle_elem.click()
            time.sleep(2)
            logger.info("Sync toggled ON on %s", emu_a.serial)

    def _trigger_sync(self, emu_a):
        emu_a._go_home(timeout=5.0)
        emu_a.open_settings()
        _adb_tap("Sync", emu_a, timeout=3)
        time.sleep(1)
        sync_toggle = emu_a.d(description="Toggle sync")
        if sync_toggle.wait(timeout=3.0):
            hint = emu_a.d(text="Enable sync to start syncing")
            if hint.exists:
                sync_toggle.click()
                time.sleep(2)
                if not hint.exists:
                    logger.info("Sync toggled ON on %s", emu_a.serial)
                else:
                    logger.warning("Sync toggle click did not dismiss hint on %s", emu_a.serial)
        sync_screen_texts = [tv.get_text() for tv in emu_a.d(className="android.widget.TextView") if tv.get_text()]
        logger.info("Sync screen texts on %s: %s", emu_a.serial, sync_screen_texts)
        ok = _adb_tap("Sync Now", emu_a, timeout=5)
        if ok:
            time.sleep(2)
            logger.info("Sync Now tapped (adb) on %s", emu_a.serial)
        else:
            logger.warning("Sync Now button not found on %s", emu_a.serial)
