import logging
import time

import uiautomator2 as u2

from .adb import AdbDevice

logger = logging.getLogger(__name__)

APP_PACKAGE = "com.picpocket.app"


class UiDevice:
    def __init__(self, serial: str):
        self.d = u2.connect(serial)
        self.serial = serial
        self.adb = AdbDevice(serial)

    def open_app(self):
        self.d.app_stop(APP_PACKAGE)
        time.sleep(1)
        self.d.app_start(APP_PACKAGE)
        deadline = time.time() + 15.0
        while time.time() < deadline:
            current = self.d.app_current()
            if current.get("package") == APP_PACKAGE:
                break
            time.sleep(1)
        time.sleep(3)
        self._go_home(timeout=15.0)
        current = self.d.app_current()
        if current.get("package") != APP_PACKAGE:
            logger.error("App not in foreground after open_app (current=%s) on %s", current, self.serial)
            self.d.app_start(APP_PACKAGE)
            time.sleep(3)
            self._go_home(timeout=10.0)
        else:
            logger.info("App opened on %s", self.serial)

    def open_settings(self):
        self.d(description="Settings").click(timeout=5)
        if self.d(text="Settings").wait(timeout=10.0):
            logger.debug("Settings opened on %s", self.serial)
        else:
            logger.error("Settings screen did not open on %s", self.serial)

    def trigger_sync(self):
        self._go_home(timeout=5.0)
        self.open_settings()
        self.d(text="Sync").click()
        time.sleep(1)
        sync_toggle = self.d(description="Toggle sync")
        if sync_toggle.wait(timeout=3.0):
            hint = self.d(text="Enable sync to start syncing")
            if hint.exists:
                sync_toggle.click()
                time.sleep(2)
                if not hint.exists:
                    logger.info("Sync toggled ON on %s", self.serial)
                else:
                    logger.warning("Sync toggle click did not dismiss hint on %s", self.serial)
        sync_now = self.d(text="Sync Now")
        if sync_now.wait(timeout=5.0):
            sync_now.click()
            time.sleep(2)
            logger.info("Sync Now clicked on %s", self.serial)
            self.adb.shell("screencap -p /sdcard/sync_now.png")
        else:
            logger.warning("Sync Now button not found on %s", self.serial)

    def import_pdf(self, pdf_name: str, timeout: float = 30.0):
        self._go_home(timeout=10.0)
        import_btn = self.d(description="Import PDF")
        if import_btn.wait(timeout=5.0):
            import_btn.click()
            time.sleep(3)
            self._navigate_saf(pdf_name, timeout)
            logger.info("PDF '%s' imported on %s", pdf_name, self.serial)
        else:
            logger.warning("Import PDF button not found on %s (current=%s)", self.serial, self.d.app_current())

    def ensure_drive_configured(self, timeout: float = 120.0):
        self.open_app()
        time.sleep(1)
        self.open_settings()
        self.d(text="Sync").click()
        time.sleep(1)
        connect_btn = self.d(text="Select Folder")
        if not connect_btn.wait(timeout=5.0):
            logger.info("Drive sync already configured on %s", self.serial)
            self.d.press("back")
            return
        connect_btn.click()
        time.sleep(2)
        self._select_nextcloud_root(timeout)
        allow_btn = self.d(text="ALLOW")
        if allow_btn.wait(timeout=5.0):
            allow_btn.click()
            time.sleep(2)
        toggle_elem = self.d(description="Toggle sync")
        if toggle_elem.wait(timeout=5.0):
            toggle_elem.click()
            time.sleep(2)
        self._go_home(timeout=10.0)
        logger.info("Drive configured on %s", self.serial)

    def assert_doc_exists(self, title: str, timeout: float = 5.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.d(text=title).exists:
                logger.info("Document '%s' visible on %s", title, self.serial)
                return True
            time.sleep(0.5)
        logger.warning("Document '%s' not found on %s after %.1fs", title, self.serial, timeout)
        return False

    def _go_home(self, timeout: float = 10.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            current = self.d.app_current()
            if current.get("package") != APP_PACKAGE:
                self.d.press("back")
                time.sleep(1)
                continue
            if self.d(text="PicPocket").exists or self.d(text="No documents yet").exists:
                return
            if self.d(text="PicPocket").wait(timeout=3.0):
                return
            self.d.press("back")
            time.sleep(1)

    def _navigate_saf(self, target_file: str, timeout: float):
        deadline = time.time() + timeout
        roots_opened = False
        while time.time() < deadline:
            if self.d(text=target_file).exists:
                self.d(text=target_file).click()
                time.sleep(3)
                logger.info("SAF: selected file '%s' on %s", target_file, self.serial)
                return
            if roots_opened:
                downloads = self.d(text="Downloads")
                if downloads.exists:
                    downloads.click()
                    time.sleep(3)
                    roots_opened = False
                    continue
            show_roots = self.d(description="Show roots")
            if show_roots.exists:
                show_roots.click()
                roots_opened = True
                time.sleep(2)
                downloads = self.d(text="Downloads")
                if downloads.exists:
                    downloads.click()
                    time.sleep(3)
                    roots_opened = False
            time.sleep(2)
        raise TimeoutError(f"Could not find '{target_file}' in SAF picker")

    def _tap_retry(self, find, description: str, timeout: float = 15.0):
        """Find and tap an element, retrying while the SAF picker UI settles.

        uiautomator2 raises RPCUnknownError/StaleObjectException when a node
        is re-rendered between lookup and tap (e.g. the roots drawer is still
        animating). Re-locate the node each attempt until it settles.
        """
        deadline = time.time() + timeout
        last_err = None
        while time.time() < deadline:
            try:
                el = find()
                if el.exists:
                    el.click(timeout=5)
                    return True
            except Exception as exc:  # noqa: BLE001 - uiautomator stale/RPC variants
                last_err = exc
                time.sleep(0.5)
                continue
            time.sleep(0.5)
        logger.error("SAF: could not tap '%s' on %s: %s", description, self.serial, last_err)
        return False

    def select_saf_folder(self, folder_name: str, timeout: float = 60.0):
        """Select a folder in the SAF picker, handling both navigation states.

        Path B (consecutive): picker already open inside the target folder
        (breadcrumb shows it) — "USE THIS FOLDER" is immediately available.
        Path A (first-time): picker opens elsewhere — tap "Show roots", open
        the Nextcloud provider, tap the target folder, confirm with
        "USE THIS FOLDER".

        USE THIS FOLDER is only ever tapped while the breadcrumb shows the
        target folder, so the Nextcloud root itself is never selected.
        """
        deadline = time.time() + timeout
        breadcrumb_id = "com.google.android.documentsui:id/breadcrumb_text"
        entered_nextcloud = False
        while time.time() < deadline:
            breadcrumb = self.d(resourceId=breadcrumb_id, text=folder_name)
            if breadcrumb.exists:
                if self._tap_retry(lambda: self.d(text="USE THIS FOLDER"),
                                   "USE THIS FOLDER"):
                    time.sleep(2)
                    logger.info("SAF: already inside '%s', tapped USE THIS FOLDER on %s",
                                folder_name, self.serial)
                    return

            if not entered_nextcloud:
                if self._tap_retry(lambda: self.d(description="Show roots"), "Show roots"):
                    time.sleep(2)
                    logger.info("SAF: roots shown on %s", self.serial)
                if self._tap_retry(lambda: self.d(text="Nextcloud"), "Nextcloud"):
                    time.sleep(3)
                    entered_nextcloud = True
                    logger.info("SAF: tapped Nextcloud provider on %s", self.serial)

            target = self.d(text=folder_name)
            if target.exists and not breadcrumb.exists:
                if self._tap_retry(lambda: self.d(text=folder_name), folder_name):
                    time.sleep(3)
                    logger.info("SAF: tapped folder '%s' in Nextcloud on %s",
                                folder_name, self.serial)
                    continue

            if entered_nextcloud:
                texts = [tv.get_text() for tv in
                         self.d(className="android.widget.TextView") if tv.get_text()]
                logger.warning(
                    "SAF: '%s' not found after entering Nextcloud on %s — visible texts: %s",
                    folder_name, self.serial, texts,
                )

            time.sleep(2)
        raise TimeoutError(f"Could not select folder '{folder_name}' in SAF picker")

    def _select_nextcloud_root(self, timeout: float):
        self.select_saf_folder("PicPocketTest", timeout)
