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

        docs = oracle.list_docs()
        matching = [d for d in docs if d["name"].startswith("test-conflict")]
        assert matching, "Doc not found in Drive after initial sync"
        doc_prefix = matching[0]["name"].split("/")[0]

        emu_a.d(text="test-conflict").click()
        time.sleep(2)
        emu_a.d(text="Rename").click()
        emu_a.d.send_keys("test-conflict-renamed")
        emu_a.d(text="OK").click()
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
