import json
import logging
import time

import pytest

from devices.pdf_utils import generate_and_push

logger = logging.getLogger(__name__)


class TestConflict:
    @pytest.fixture(autouse=True)
    def setup(self, reset_state, ensure_drive_configured, emu_a, oracle):
        self.emu_a = emu_a
        self.oracle = oracle

    def test_divergent_edits_detected(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "test-conflict", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-conflict.pdf")
        time.sleep(3)
        emu_a.trigger_sync()
        watcher_a.wait_for_sync()

        doc_prefix = oracle.wait_for_doc_folder()
        assert doc_prefix, "Doc not found in Drive after initial sync"

        emu_a._go_home(timeout=15.0)
        assert emu_a._adb_tap("test-conflict", timeout=10.0), "Doc not found for rename"
        time.sleep(2)
        assert emu_a._adb_tap_desc("More options", timeout=10.0), "More options not found"
        assert emu_a._adb_tap("Rename", timeout=10.0), "Rename action not found"
        time.sleep(1)
        rename_field = emu_a.d(className="android.widget.EditText")
        assert rename_field.wait(timeout=5.0), "Rename text field not found"
        rename_field.set_text("test-conflict-renamed")
        time.sleep(1)
        assert emu_a._adb_tap("Save", timeout=10.0), "Rename Save not found"
        time.sleep(1)
        emu_a.trigger_sync()
        watcher_a.wait_for_sync()

        metadata = oracle.read_metadata(doc_prefix)
        assert metadata is not None, "metadata.json not found after rename"
        metadata["name"] = "test-conflict-divergent"
        metadata["syncVersion"] = metadata.get("syncVersion", 1) + 2
        oracle.write_file(
            f"{doc_prefix}/metadata.json",
            json.dumps(metadata),
            "application/json",
        )

        emu_a.trigger_sync()
        result = watcher_a.wait_for_pattern(
            __import__("re").compile(r"detectConflicts")
        )
        logger.info("Conflict detected: %s", result)
        watcher_a.wait_for_sync()

        emu_a.open_settings()
        emu_a.d(text="Sync").click()
        time.sleep(2)
        conflicts_badge = emu_a.d(textContains="Conflicts")
        assert conflicts_badge.wait(timeout=10.0), "Conflict UI did not surface conflicting doc"
