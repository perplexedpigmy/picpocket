import logging
import time

import pytest

from devices.pdf_utils import generate_and_push
from devices.tracing import NO_NETWORK, sync_with_false_mutex_retry
from scenarios._integrity import assert_drive_verified

logger = logging.getLogger(__name__)


class TestOfflineSync:
    """A sync with no connectivity must be skipped, not crash or lose data.

    The app's connectivity check returns false while the radio is down, so the
    sync aborts immediately with "performSync: no network". The imported doc
    must remain intact locally, and a sync after connectivity returns must
    upload it normally.
    """

    @pytest.fixture(autouse=True)
    def setup(self, reset_state, ensure_drive_configured, emu_a, oracle):
        self.emu_a = emu_a
        self.oracle = oracle

    def test_offline_sync_is_skipped_and_recovered(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "test-offline", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-offline.pdf")
        time.sleep(3)

        # Drop the data path (wifi is already disabled by _stabilize_network;
        # eth0/cellular is the only route to 10.0.2.2:8080).
        emu_a.adb.shell("svc data disable", timeout=15)
        time.sleep(6)

        try:
            emu_a.trigger_sync()
            watcher_a.wait_for_pattern(NO_NETWORK, timeout=60.0)

            emu_a._go_home(timeout=10.0)
            # The offline sync aborts before touching the local store, so the
            # doc must still be present on disk. Verify the store directly
            # rather than the home UI: the UI check raced recomposition after
            # the sync-error flow and flaked. The store is the ground truth
            # for "must remain intact locally".
            doc_id = emu_a.find_local_doc("test-offline")
            assert doc_id, "Local doc lost while offline (missing from local store)"
            meta = emu_a.read_device_metadata(doc_id)
            pages = (meta or {}).get("pages") or []
            assert pages, f"Local doc {doc_id} has no pages after offline sync"
            page_filenames = [p.get("filename") for p in pages if p.get("filename")]
            assert page_filenames, f"Local metadata for {doc_id} lists no page files"
            for filename in page_filenames[:1]:
                assert emu_a.page_file_exists(doc_id, filename, timeout=10.0), (
                    f"Page file {filename} missing locally after offline sync"
                )
            logger.info(
                "Offline integrity verified: %d pages on disk for %s on %s",
                len(page_filenames), doc_id, emu_a.serial,
            )
        finally:
            emu_a.adb.shell("svc data enable", timeout=15)

        # Connectivity returns; the same doc now uploads.
        time.sleep(8)
        sync_with_false_mutex_retry(emu_a, watcher_a)
        assert_drive_verified(oracle, drive_finality=True)
        emu_a._go_home(timeout=10.0)
        assert emu_a.find_local_doc("test-offline"), (
            "Doc missing from local store after online recovery sync"
        )
        assert emu_a.assert_doc_exists("test-offline"), (
            "Doc not visible after online recovery sync"
        )
