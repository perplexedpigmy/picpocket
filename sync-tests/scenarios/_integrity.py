"""Shared server-side content-integrity assertions for scenario tests.

The happy-path scenarios used to assert only that *something* appeared on the
server (folder exists, listing non-empty), which passed even when every
uploaded file was 0 bytes. These helpers are the honest version: every file
under every synced doc folder must reach a non-zero content length on the
WebDAV side, keep that content across a short settle window, and land on
Google Drive finalized (non-zero size, real MD5, no partial-download residue).

Underscore-prefixed so pytest's scenario collector never treats this as a test
module.
"""

import logging
import time

from infra.nextcloud import wait_drive_finalized

logger = logging.getLogger(__name__)


def assert_doc_integrity(oracle, folder, settle=5.0, on_file_fail=None) -> dict:
    """Assert every file in one doc folder has non-zero content length on the
    WebDAV side, then re-check after `settle` seconds that the content
    persisted (i.e. the server does not eventually settle back to 0 bytes).

    Returns {filename: content_length} so callers can size-match another
    device's local copy against the server truth.

    on_file_fail, if given, is called with (folder, child) just before a
    failure is raised, so tests can dump forensic state (e.g. logcat, occ
    scan, Drive rows) for the exact file that broke.
    """
    lengths = {}
    for child, is_col in oracle._list_entries(f"/PicPocketTest/{folder}"):
        if is_col:
            continue
        rel = f"{folder}/{child}"
        if not oracle.verify_file_complete(rel, expected_bytes=1, timeout=60):
            if on_file_fail is not None:
                on_file_fail(folder, child)
            assert False, f"WebDAV file not complete: {rel}"
        lengths[child] = oracle._child_length(rel) or 0

    # An empty doc folder means the upload never landed (e.g. the client's
    # createFolder MKCOL reached the server but the response timed out, leaving
    # a bare folder). Never let that pass as a successful sync.
    assert lengths, (
        f"Doc folder {folder} has no files on the server (upload never landed)"
    )

    if settle > 0:
        time.sleep(settle)
        for child in list(lengths):
            rel = f"{folder}/{child}"
            cur = oracle._child_length(rel)
            if cur is None or cur == 0:
                if on_file_fail is not None:
                    on_file_fail(folder, child)
                assert False, f"WebDAV file lost its content after settle: {rel}"
            lengths[child] = cur
    return lengths


def assert_drive_verified(oracle, drive_timeout=180.0, on_file_fail=None) -> dict:
    """Assert the sync landed end-to-end with verified completion.

    B1: every file under each synced doc folder is fully written on the
    WebDAV side (non-zero content length, stable across a settle window).
    B2: the Drive copy is finalized — no 'application/x-partial-download'
    residue, every file has a size and a real MD5.

    Returns {doc_folder: {filename: content_length}} so callers can
    size-match another device's local copy against the server truth.
    """
    entries = oracle._list_entries("/PicPocketTest")
    doc_folders = [
        name for name, is_col in entries if is_col and not name.startswith(".")
    ]
    logger.info("Drive folder after sync: %s", entries)
    assert doc_folders, "No doc folders found on Drive after sync"

    doc_lengths = {}
    for folder in doc_folders:
        doc_lengths[folder] = assert_doc_integrity(
            oracle, folder, on_file_fail=on_file_fail
        )

    finalized = wait_drive_finalized("", min_size=0, timeout=drive_timeout)
    assert finalized, "Drive folder empty after sync"
    partial = [
        r["Path"] for r in finalized if r.get("MimeType") == "application/x-partial-download"
    ]
    assert not partial, f"Partial-download residue on Drive: {partial}"
    no_hash = [r["Path"] for r in finalized if not (r.get("Hashes") or {}).get("md5")]
    assert not no_hash, f"Files without MD5 on Drive: {no_hash}"
    zeros = [r["Path"] for r in finalized if r.get("Size") == 0]
    assert not zeros, f"0-byte files on Drive (failed-write residue): {zeros}"
    paths = [r["Path"] for r in finalized]
    dups = sorted({p for p in paths if paths.count(p) > 1})
    assert not dups, f"Duplicate file objects on Drive: {dups}"
    logger.info("Drive finality verified: %d file(s), no partial-download residue", len(finalized))
    return doc_lengths
