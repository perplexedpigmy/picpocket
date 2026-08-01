import logging
import time

import pytest

from devices.pdf_utils import generate_and_push

logger = logging.getLogger(__name__)


class TestEncryption:
    @pytest.fixture(autouse=True)
    def setup(self, reset_state, ensure_drive_configured, emu_a):
        self.emu_a = emu_a

    def test_encrypted_drive_blocks_unauthenticated(
        self, emu_a, watcher_a
    ):
        generate_and_push(emu_a.adb, "test-enc", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-enc.pdf")
        time.sleep(3)
        emu_a.trigger_sync()
        watcher_a.wait_for_sync()

        emu_a.open_settings()
        emu_a.d(text="Encryption").click()
        time.sleep(1)
        emu_a.d(text="Enable encryption").click()
        emu_a.d.send_keys("test-passphrase-123")
        emu_a.d(text="OK").click()
        time.sleep(2)
        emu_a.trigger_sync()
        watcher_a.wait_for_sync()

        emu_a.adb.clear_app_data()
        time.sleep(2)

        emu_a.open_app()
        emu_a.open_settings()
        emu_a.d(text="Sync").click()
        time.sleep(1)
        emu_a.d(text="Enable sync").click()
        time.sleep(2)
        emu_a._navigate_saf_folder("PicPocketTest", timeout=60.0)
        emu_a.d.press("back")
        emu_a.open_app()

        emu_a.trigger_sync()
        result = watcher_a.wait_for_pattern(
            __import__("re").compile(r"remoteEncrypted=true, passphraseSet=false")
        )
        logger.info("Encryption gating: %s", result)

        error_text = emu_a.d(textContains="This Drive is encrypted")
        assert error_text.wait(timeout=10.0), "Encryption error not shown"

        emu_a.d(text="Enter passphrase").click()
        emu_a.d.send_keys("test-passphrase-123")
        emu_a.d(text="OK").click()
        time.sleep(1)

        emu_a.trigger_sync()
        watcher_a.wait_for_sync()

        assert emu_a.assert_doc_exists("test-enc"), "Doc not visible after passphrase"
