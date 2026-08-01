import logging
import time

import pytest

from devices.pdf_utils import generate_and_push

logger = logging.getLogger(__name__)


class TestOrphan:
    def test_delete_propagation_and_orphan_dismissal(
        self, emu_a, emu_b, watcher_a, watcher_b, oracle, two_devices
    ):
        generate_and_push(emu_a.adb, "test-orphan", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-orphan.pdf")
        time.sleep(3)
        emu_a.trigger_sync()
        watcher_a.wait_for_sync()
        assert emu_a.assert_doc_exists("test-orphan"), "Doc not visible on device A"

        emu_b.open_app()
        emu_b.trigger_sync()
        watcher_b.wait_for_sync()
        assert emu_b.assert_doc_exists("test-orphan"), "Doc not visible on device B"

        emu_a.d(text="test-orphan").long_click()
        time.sleep(1)
        emu_a.d(text="Delete").click()
        emu_a.d(text="OK").click()
        time.sleep(2)
        emu_a.trigger_sync()
        watcher_a.wait_for_sync()

        emu_b.trigger_sync()
        watcher_b.wait_for_sync()

        assert not emu_b.assert_doc_exists("test-orphan"), "Doc still visible on device B after delete"

        emu_b.open_settings()
        emu_b.d(text="Deleted Documents").click()
        time.sleep(2)
        assert emu_b.assert_doc_exists("test-orphan"), "Orphan not shown in Deleted Documents"

        emu_b.d(text="Dismiss").click()
        time.sleep(1)
        emu_b.trigger_sync()
        watcher_b.wait_for_sync()

        all_files = oracle.list_all_files("/PicPocketTest")
        tombstone_docs = [f for f in all_files if "tombstone" in f]
        logger.info("Tombstone files after dismiss: %s", tombstone_docs)
