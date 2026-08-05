import json
import logging
import time

import pytest

from devices.pdf_utils import generate_and_push

logger = logging.getLogger(__name__)


class TestRemoteAddPage:
    @pytest.fixture(autouse=True)
    def setup(self, reset_state, ensure_drive_configured, emu_a, oracle):
        self.emu_a = emu_a
        self.oracle = oracle

    def test_oracle_adds_page_device_downloads(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "test-remote", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-remote.pdf")
        time.sleep(3)

        emu_a.trigger_sync()
        watcher_a.wait_for_sync()

        doc_prefix = oracle.wait_for_doc_folder()
        assert doc_prefix, "Doc not found in Drive after initial sync"
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

        # Probe: the remote-added page must actually be on the server. The
        # app sees it only after the Nextcloud-client bridge refreshes its
        # folder listing, so verify the server directly (WebDAV).
        server_files = oracle.list_files(f"/PicPocketTest/{doc_prefix}")
        assert "page_004.jpg" in server_files, (
            f"page_004.jpg missing on server: {server_files}"
        )

        # Round 1: prime the bridge. The app's sync scans the client's cached
        # folder listing, which is stale right after an out-of-band server
        # write, so this sync misses the new page but its folder access
        # triggers the client's on-demand RefreshFolderOperation.
        emu_a.trigger_sync()
        watcher_a.wait_for_sync()
        assert watcher_a.wait_for_client_refresh(doc_prefix), (
            "Nextcloud client did not refresh doc folder after sync"
        )

        # Round 2: the client listing is now fresh. Force a download by
        # setting the remote syncVersion one above the device's local version
        # (adjacent versions => no conflict, remote > local => download).
        device_meta = emu_a.read_device_metadata(doc_prefix)
        assert device_meta is not None, "device metadata.json missing after sync"
        device_meta["syncVersion"] = device_meta.get("syncVersion", 0) + 1
        oracle.write_file(
            f"{doc_prefix}/metadata.json",
            json.dumps(device_meta),
            "application/json",
        )

        emu_a.trigger_sync()
        result = watcher_a.wait_for_pattern(
            __import__("re").compile(r"downloadFullDocument")
        )
        logger.info("Sync logcat line: %s", result)
        watcher_a.wait_for_sync()

        emu_a._go_home(timeout=15.0)
        assert emu_a.assert_doc_exists("test-remote"), "Doc missing after page add"

        assert emu_a.page_file_exists(doc_prefix, "page_004.jpg"), (
            "Device did not download remote-added page page_004.jpg"
        )
