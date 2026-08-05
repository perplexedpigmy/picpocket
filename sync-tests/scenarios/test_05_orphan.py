import logging
import time

import pytest

from devices.pdf_utils import generate_and_push

logger = logging.getLogger(__name__)


class TestOrphan:
    def test_delete_propagation_and_orphan_surfacing(
        self, emu_a, emu_b, watcher_a, watcher_b, oracle, two_devices,
        reset_state, reset_state_b
    ):
        generate_and_push(emu_a.adb, "test-orphan", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-orphan.pdf")
        time.sleep(3)
        emu_a.trigger_sync()
        watcher_a.wait_for_sync()
        assert emu_a.assert_doc_exists("test-orphan"), "Doc not visible on device A"

        emu_b.ensure_drive_configured()
        emu_b.trigger_sync()
        watcher_b.wait_for_sync()
        assert emu_b.assert_doc_exists("test-orphan"), "Doc not visible on device B"

        # Delete on A: long-press -> selection mode -> "Delete selected" icon
        # -> confirm "Delete" in the "Delete documents?" dialog.
        emu_a.d(text="test-orphan").long_click()
        time.sleep(1)
        delete_selected = emu_a.d(description="Delete selected")
        assert delete_selected.wait(timeout=5.0), "Delete selected action not found"
        delete_selected.click()
        time.sleep(1)
        confirm_delete = emu_a.d(text="Delete")
        assert confirm_delete.wait(timeout=5.0), "Delete confirm button not found"
        confirm_delete.click()
        time.sleep(2)
        emu_a.trigger_sync()
        watcher_a.wait_for_sync()

        emu_b.trigger_sync()
        watcher_b.wait_for_sync()

        assert not emu_b.assert_doc_exists("test-orphan"), "Doc still visible on device B after delete"

        # The deletion propagates to B as an orphan surfaced on the Sync screen
        # ("Removed by Others" badge). NOTE: the Deleted Documents screen has no
        # UI entry point yet, so dismissal/acknowledgment is not reachable here.
        emu_b.open_settings()
        emu_b.d(text="Sync").click()
        time.sleep(2)
        removed = emu_b.d(textContains="Removed by Others")
        assert removed.wait(timeout=10.0), "Orphan not surfaced as Removed by Others on B"
