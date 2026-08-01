import logging
import time

import pytest

from devices.pdf_utils import generate_and_push

logger = logging.getLogger(__name__)


class TestHappyPath:
    @pytest.fixture(autouse=True)
    def setup(self, reset_state, ensure_drive_configured, emu_a):
        self.emu_a = emu_a

    def test_a_imports_3page_pdf_drive_verifies(
        self, emu_a, watcher_a, oracle
    ):
        generate_and_push(emu_a.adb, "test-3page", pages=3)
        emu_a.open_app()
        emu_a.import_pdf("test-3page.pdf")
        time.sleep(3)

        emu_a.trigger_sync()
        watcher_a.wait_for_sync()

        emu_a._go_home(timeout=10.0)
        assert emu_a.assert_doc_exists("test-3page"), "Doc not visible"

        drive_files = oracle.list_files("/PicPocketTest")
        logger.info("Drive folder contents: %s", drive_files)
        assert drive_files, "Drive folder empty after sync"

    def test_b_downloads_from_other_device(
        self, emu_a, emu_b, watcher_a, watcher_b, oracle, two_devices
    ):
        emu_a.open_app()
        emu_a.open_settings()
        emu_a.trigger_sync()
        watcher_a.wait_for_sync()

        emu_b.open_app()
        emu_b.trigger_sync()
        watcher_b.wait_for_sync()

        assert emu_b.assert_doc_exists("test-3page"), "Doc not visible on device B"
