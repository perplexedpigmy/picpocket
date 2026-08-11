import time

import pytest

from devices.pdf_utils import generate_and_push
from devices.tracing import sync_until, sync_with_false_mutex_retry
from scenarios._integrity import assert_drive_verified


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

        sync_with_false_mutex_retry(emu_a, watcher_a)

        emu_a._go_home(timeout=10.0)
        assert emu_a.assert_doc_exists("test-3page"), "Doc not visible"

        assert_drive_verified(oracle)

    def test_b_downloads_from_other_device(
        self, emu_a, emu_b, watcher_a, watcher_b, oracle, two_devices, reset_state_b
    ):
        generate_and_push(emu_a.adb, "test-3page", pages=3)
        emu_a.open_app()
        emu_a.import_pdf("test-3page.pdf")
        time.sleep(3)
        sync_with_false_mutex_retry(emu_a, watcher_a)

        doc_prefix = oracle.wait_for_doc_folder()
        assert doc_prefix, "Doc not found in Drive after A sync"

        server_lengths = assert_drive_verified(oracle)[doc_prefix]

        emu_b.ensure_drive_configured()

        def _downloaded():
            meta = emu_b.read_device_metadata(doc_prefix)
            if not meta:
                return False
            pages = meta.get("pages", [])
            if not pages:
                return False
            first = pages[0]["filename"]
            if not emu_b.page_file_exists(doc_prefix, first, timeout=5.0):
                return False
            size = emu_b.page_file_size(doc_prefix, first)
            return size is not None and size > 0 and size == server_lengths.get(first)

        assert sync_until(emu_b, watcher_b, _downloaded), (
            "Doc not downloaded on device B: page sizes must match the server"
        )

        emu_b._go_home(timeout=10.0)
        assert emu_b.assert_doc_exists("test-3page"), "Doc not visible on device B"
