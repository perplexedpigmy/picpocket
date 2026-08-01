import json
import logging
import time

import pytest

from devices.pdf_utils import generate_and_push

logger = logging.getLogger(__name__)


class TestRemoteAddPage:
    @pytest.fixture(autouse=True)
    def setup(self, reset_state, ensure_drive_configured, emu_a, emu_b, oracle):
        self.emu_a = emu_a
        self.emu_b = emu_b
        self.oracle = oracle

    def test_oracle_adds_page_device_downloads(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "test-remote", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-remote.pdf")
        time.sleep(3)

        emu_a.trigger_sync()
        watcher_a.wait_for_sync()

        docs = oracle.list_docs()
        matching = [d for d in docs if d["name"].startswith("test-remote")]
        assert matching, "Doc not found in Drive after initial sync"

        doc_prefix = matching[0]["name"].split("/")[0]
        oracle.write_file(
            f"{doc_prefix}/page_004.jpg",
            "mock image data",
            "image/jpeg",
        )

        metadata = oracle.read_metadata(doc_prefix)
        assert metadata is not None, "metadata.json not found"
        metadata["pageCount"] = 4
        metadata["syncVersion"] = metadata.get("syncVersion", 1) + 1
        oracle.write_file(
            f"{doc_prefix}/metadata.json",
            json.dumps(metadata),
            "application/json",
        )

        emu_a.trigger_sync()
        result = watcher_a.wait_for_pattern(
            __import__("re").compile(r"checkFiles|downloadFullDocument")
        )
        logger.info("Sync logcat line: %s", result)
        watcher_a.wait_for_sync()

        assert emu_a.assert_doc_exists("test-remote"), "Doc missing after page add"
