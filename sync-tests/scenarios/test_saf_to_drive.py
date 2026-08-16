import logging
import time

import pytest

from devices.pdf_utils import generate_and_push
from devices.ui import worker_root_folder
from infra import nextcloud as nc
from scenarios._integrity import assert_drive_verified

logger = logging.getLogger(__name__)


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

    def test_select_picpockettest_folder(self, emu_a, fresh_sync_config):
        # fresh_sync_config: wipe the saved folder selection so this test (and
        # only this test) drives the full Nextcloud SAF picker. The other chain
        # tests keep the selection and skip the ~30-45s SAF re-navigation.
        self._select_picpockettest_folder(emu_a)

        toggle = emu_a.d(description="Toggle sync")
        assert toggle.wait(timeout=10), "Sync toggle not visible — folder selection may have failed"

    def test_small_file_through_chain(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "saf-test", pages=1)
        self._select_picpockettest_folder(emu_a)
        self._toggle_sync_on(emu_a)

        emu_a.import_pdf("saf-test.pdf")
        time.sleep(3)

        self._trigger_sync(emu_a)
        watcher_a.wait_for_sync()

        self._assert_verified_completion(oracle, emu_a)

    def test_large_file_through_chain(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "saf-large", pages=50)
        self._select_picpockettest_folder(emu_a, timeout=90)
        self._toggle_sync_on(emu_a)

        emu_a.import_pdf("saf-large.pdf")
        time.sleep(3)

        self._trigger_sync(emu_a)
        watcher_a.wait_for_sync(timeout=300)

        self._assert_verified_completion(oracle, emu_a, drive_timeout=300)

    def test_after_reinstall(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "saf-reinstall", pages=1)
        self._select_picpockettest_folder(emu_a)
        self._toggle_sync_on(emu_a)

        emu_a.import_pdf("saf-reinstall.pdf")
        time.sleep(3)

        self._trigger_sync(emu_a)
        watcher_a.wait_for_sync()

        self._assert_verified_completion(oracle, emu_a)

    def _assert_verified_completion(self, oracle, emu_a=None, drive_timeout=180):
        """Assert the sync landed end-to-end with verified completion.

        B1: every file under each synced doc folder is fully written on the
        WebDAV side (non-zero content length).
        B2: the Drive copy is finalized — no 'application/x-partial-download'
        residue, every file has a size and a real MD5.

        Delegates to scenarios._integrity; on a WebDAV-length failure the
        forensic dump snapshots server/Drive/app state for the exact file.
        """
        assert_drive_verified(
            oracle,
            drive_timeout=drive_timeout,
            drive_finality=True,
            on_file_fail=lambda folder, child: self._forensic_dump(
                oracle, folder, child, emu_a
            ),
        )

    def _forensic_dump(self, oracle, folder, child, emu_a):
        """Snapshot server/Drive/app state for a file that failed B1.

        Runs inside the test body, i.e. BEFORE the setup fixture's teardown
        clears the remote folder. Aim: decide whether the 0-byte file is real
        server truth, a stale server filecache entry, or a client-side phantom
        — and whether anything (occ scan, time, Drive write-back) cleans it.
        """
        import requests

        rel = f"{folder}/{child}"
        logger.info("=== forensic dump: %s ===", rel)
        try:
            exists, length = oracle.head_file(rel)
            logger.info("  PROPFIND depth:0 -> exists=%s length=%s", exists, length)
        except Exception as e:
            logger.warning("  head_file failed: %s", e)
        try:
            resp = requests.get(oracle._webdav_url(rel), auth=oracle.auth, timeout=10)
            logger.info("  GET -> status=%d length=%d", resp.status_code, len(resp.content))
        except Exception as e:
            logger.warning("  GET failed: %s", e)
        logger.info("  folder %s contents:", folder)
        for c, is_col in oracle._list_entries(f"/PicPocketTest/{folder}"):
            if is_col:
                continue
            logger.info("    %s length=%s", c, oracle._child_length(f"{folder}/{c}"))
        logger.info("  running occ files:scan --all (server filecache vs disk)")
        nc._occ(["files:scan", "--all"], check=False)
        time.sleep(3)
        logger.info("  after scan: length=%s", oracle._child_length(rel))
        time.sleep(20)
        logger.info("  after +20s: length=%s", oracle._child_length(rel))
        try:
            rows = nc.drive_state()
            match = [r for r in rows if r.get("Path", "").startswith(folder)]
            logger.info("  Drive rows under %s: %s", folder, match)
        except Exception as e:
            logger.warning("  drive_state failed: %s", e)
        if emu_a is not None:
            out = emu_a.adb.logcat() or ""
            lines = [l for l in out.splitlines() if child in l]
            logger.info("  app logcat mentioning %s (%d lines):", child, len(lines))
            for l in lines[-50:]:
                logger.info("    %s", l)

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

        emu_a.select_saf_folder(worker_root_folder(), timeout)

        allow_btn = emu_a.d(text="ALLOW")
        if allow_btn.wait(timeout=5):
            allow_btn.click()
            time.sleep(2)
        logger.info("SAF: PicPocketTest selected on %s", emu_a.serial)

    def _toggle_sync_on(self, emu_a):
        # When sync is already configured (the chain tests keep the selection),
        # _select_picpockettest_folder skipped the picker and left us on the
        # settings list — so navigate into the Sync sub-screen explicitly. The
        # "Enable sync to start syncing" hint shows only while sync is OFF.
        emu_a._go_home(timeout=5.0)
        emu_a.open_settings()
        _adb_tap("Sync", emu_a, timeout=3)
        time.sleep(1)
        hint = emu_a.d(text="Enable sync to start syncing")
        if not hint.exists:
            logger.info("Sync already ON on %s", emu_a.serial)
            return
        # The picker may still be closing, so the toggle can go stale between
        # finding it and tapping it. Resolve fresh coordinates per attempt and
        # retry until the hint clears or the budget runs out.
        deadline = time.time() + 15.0
        while time.time() < deadline:
            toggle_elem = emu_a.d(description="Toggle sync")
            if toggle_elem.exists and not hint.exists:
                logger.info("Sync already ON on %s", emu_a.serial)
                return
            if toggle_elem.exists:
                try:
                    info = emu_a.d.jsonrpc.objInfo(toggle_elem.selector)
                    b = info["bounds"]
                    cx = (b["left"] + b["right"]) // 2
                    cy = (b["top"] + b["bottom"]) // 2
                    emu_a.adb.shell(f"input tap {cx} {cy}")
                    time.sleep(1)
                    if not hint.exists:
                        logger.info("Sync toggled ON on %s", emu_a.serial)
                        return
                except Exception as e:
                    logger.warning("Toggle tap raced on %s: %s", emu_a.serial, e)
            time.sleep(0.5)
        logger.warning("Could not confirm sync toggle ON on %s", emu_a.serial)

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
