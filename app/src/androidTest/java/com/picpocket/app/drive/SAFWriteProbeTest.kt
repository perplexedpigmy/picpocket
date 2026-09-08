package com.picpocket.app.drive

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.OutputStream
import java.security.MessageDigest
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Standalone SAF provider probe: drives the Nextcloud DocumentsProvider
 * directly (no app sync logic, no encryption) and logs a timestamped trace
 * under the [TAG] tag so a host-side WebDAV poller can correlate server state.
 *
 * Variants (all files created in the root of the granted tree):
 *   A  createDocument only — no content.
 *   B  createDocument then openOutputStream + write + close (the app's
 *      new-file sequence for a first-ever page / devices.json).
 *   C  openOutputStream("rwt"->"wt"->plain) overwrite-in-place on B's file
 *      (the app's existing-file overwrite sequence).
 *   D  createDocument then open + close without writing (mid-write failure
 *      leaves the file empty), then rollback-delete — the app's failed-write
 *      recovery: no permanent 0-byte file should survive on Drive.
 */
@RunWith(AndroidJUnit4::class)
class SAFWriteProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        val treeUri = treeUriOrNull()
        if (treeUri != null) {
            for (name in CLEANUP_NAMES) {
                deleteQuietly(treeUri, name)
            }
        }
    }

    @Test
    fun providerCreateAndWriteTimeline() {
        val treeUri = requireTreeUri()
        log("treeUri=$treeUri docId=${DocumentsContract.getTreeDocumentId(treeUri)}")
        log("C1 md5=${md5(C1)} C2 md5=${md5(C2)}")

        val aUri = create(treeUri, PROBE_A)
        log("A create only $PROBE_A -> $aUri")
        SystemClock.sleep(PAUSE_MS)

        val bUri = create(treeUri, PROBE_B)
        log("B created $PROBE_B -> $bUri")
        write(bUri, C1, listOf(null))
        log("B wrote ${C1.size} bytes to $PROBE_B and closed")
        SystemClock.sleep(PAUSE_MS)

        write(bUri, C2, listOf("rwt", "wt", null))
        log("C overwrote $PROBE_B with ${C2.size} bytes via rwt->wt->plain")
        SystemClock.sleep(PAUSE_MS)

        readBack(aUri, "A", expected = null)
        readBack(bUri, "B/C", expected = C2)

        log("done")
    }

    @Test
    fun providerCreateAbortRollbackTimeline() {
        val treeUri = requireTreeUri()
        val dUri = create(treeUri, PROBE_D)
        log("D created $PROBE_D -> $dUri")
        SystemClock.sleep(PAUSE_MS)

        val os = context.contentResolver.openOutputStream(dUri)
        assertNotNull("openOutputStream null for $PROBE_D", os)
        os!!.close()
        log("D aborted write (opened + closed without content), file still empty")
        SystemClock.sleep(PAUSE_MS)

        val deleted = DocumentsContract.deleteDocument(context.contentResolver, dUri)
        log("D rollback deleted $PROBE_D result=$deleted")
        SystemClock.sleep(PAUSE_MS)

        val present = try {
            context.contentResolver.openInputStream(dUri)?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
        log("D post-rollback still present=$present")

        log("done")
    }

    /**
     * Mutex lock-once probe (same device): create a DIRECTORY, then create
     * the same directory name again via SAF. If the provider surfaces the
     * duplicate as a failure (null), exclusive-create works (MKCOL 405); if
     * it returns the existing dir or a "(1)" conflict copy, it doesn't.
     */
    @Test
    fun createDirTwiceSameDevice() {
        val tree = requireTreeUri()
        log("LOCK: children before=${childNames(tree)}")
        val first = createDoc(tree, PROBE_LOCK_DIR, DocumentsContract.Document.MIME_TYPE_DIR)
        log("LOCK: 1st dir create -> ${describe(first)}")
        SystemClock.sleep(2000)
        val second = createDoc(tree, PROBE_LOCK_DIR, DocumentsContract.Document.MIME_TYPE_DIR)
        log("LOCK: 2nd dir create (same name) -> ${describe(second)}")
        log("LOCK: children after=${childNames(tree)}")
        log("done")
    }

    /**
     * Mutex lock-once probe (same device, file variant): create a FILE twice.
     * A file's second create either returns the existing doc (overwrite),
     * fails, or makes a "(1)" copy — this classifies which.
     */
    @Test
    fun createFileTwiceSameDevice() {
        val tree = requireTreeUri()
        log("LOCK: children before=${childNames(tree)}")
        val first = createDoc(tree, PROBE_LOCK_FILE, "application/octet-stream")
        log("LOCK: 1st file create -> ${describe(first)}")
        writeMarker(first, "A1")
        SystemClock.sleep(2000)
        val second = createDoc(tree, PROBE_LOCK_FILE, "application/octet-stream")
        log("LOCK: 2nd file create (same name) -> ${describe(second)}")
        writeMarker(second, "A2")
        log("LOCK: children after=${childNames(tree)}")
        log("done")
    }

    /**
     * Mutex lock-once probe (OTHER device): attempt to create names that
     * already exist on the server — some created by device A via SAF, some
     * created out-of-band (curl MKCOL/PUT, guaranteed invisible to this
     * device's client DB). Logs whether this device's DB sees each name
     * before the attempt, what createDocument returns, and the children
     * afterwards, so the host can tell "failed" from "returned existing"
     * from "made a (1) conflict copy".
     */
    @Test
    fun createExistingNamesCrossDevice() {
        val tree = requireTreeUri()
        for (name in CROSS_DEVICE_DIRS) {
            log("LOCK: [dir $name] children before=${childNames(tree)}")
            val attempt = createDoc(tree, name, DocumentsContract.Document.MIME_TYPE_DIR)
            log("LOCK: [dir $name] create -> ${describe(attempt)}")
            log("LOCK: [dir $name] children after=${childNames(tree)}")
        }
        for (name in CROSS_DEVICE_FILES) {
            log("LOCK: [file $name] children before=${childNames(tree)}")
            val attempt = createDoc(tree, name, "application/octet-stream")
            log("LOCK: [file $name] create -> ${describe(attempt)}")
            writeMarker(attempt, "B")
            log("LOCK: [file $name] children after=${childNames(tree)}")
        }
        log("done")
    }

    private fun createDoc(treeUri: Uri, name: String, mime: String): Uri? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        return try {
            DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
        } catch (e: Exception) {
            log("createDoc($name) THREW ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun childNames(treeUri: Uri): List<String> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        val names = mutableListOf<String>()
        try {
            context.contentResolver.query(childrenUri, null, null, null, null)
                ?.use { c ->
                    while (c.moveToNext()) {
                        val n = c.getString(
                            c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        )
                        names.add(n)
                    }
                }
        } catch (e: Exception) {
            log("childNames THREW ${e.javaClass.simpleName}: ${e.message}")
        }
        return names
    }

    private fun describe(uri: Uri?): String {
        if (uri == null) return "NULL"
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return "URI(query empty)"
                val name = c.getString(
                    c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                )
                val mime = c.getString(
                    c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE),
                )
                "name='$name' mime=$mime uri=$uri"
            } ?: "URI(query null)"
        } catch (e: Exception) {
            "URI(query THREW ${e.javaClass.simpleName}: ${e.message})"
        }
    }

    private fun writeMarker(uri: Uri?, marker: String) {
        if (uri == null) return
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(marker.toByteArray()) }
            log("wrote marker '$marker' to $uri")
        } catch (e: Exception) {
            log("writeMarker THREW ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Hypothesis probe A: repeated in-place overwrite of the SAME existing
     * document via openOutputStream("rwt"->"wt"->plain) — the working-tree
     * writeFileTo path. If the server is left at 0/stale bytes at the end,
     * this path is the regression.
     */
    @Test
    fun overwriteInPlaceRepeatedly() {
        val treeUri = requireTreeUri()
        val uri = create(treeUri, PROBE_RW)
        write(uri, C1, listOf(null))
        log("OVERWRITE created + first write ${C1.size}B")
        SystemClock.sleep(PAUSE_MS)
        for (i in 0 until REWRITE_CYCLES) {
            write(uri, C2, listOf("rwt", "wt", null))
            log("OVERWRITE cycle=$i wrote ${C2.size}B")
            SystemClock.sleep(PAUSE_MS)
        }
        readBack(uri, "OVERWRITE-final", expected = C2)
        log("done")
    }

    /**
     * Hypothesis probe B (control): same loop, but each cycle deletes the
     * document and recreates it fresh before writing — the old writeFile
     * (HEAD) behavior. If this stays clean while probe A loses bytes, the
     * in-place path is the differentiator.
     */
    @Test
    fun deleteAndRecreateRepeatedly() {
        val treeUri = requireTreeUri()
        var uri = create(treeUri, PROBE_RC)
        write(uri, C1, listOf(null))
        log("RECREATE created + first write ${C1.size}B")
        SystemClock.sleep(PAUSE_MS)
        for (i in 0 until REWRITE_CYCLES) {
            val deleted = DocumentsContract.deleteDocument(context.contentResolver, uri)
            log("RECREATE cycle=$i deleted=$deleted")
            uri = create(treeUri, PROBE_RC)
            write(uri, C2, listOf("rwt", "wt", null))
            log("RECREATE cycle=$i recreated + wrote ${C2.size}B")
            SystemClock.sleep(PAUSE_MS)
        }
        readBack(uri, "RECREATE-final", expected = C2)
        log("done")
    }

    /**
     * Hypothesis probe C: scale/burst — many distinct files created and
     * written in tight succession (the 50-page import shape). Isolates
     * whether the plain fresh-create leg alone loses content at volume.
     */
    @Test
    fun scaleCreateWriteBurst() {
        val treeUri = requireTreeUri()
        for (i in 0 until BURST_FILES) {
            val name = "burst_%03d.bin".format(i)
            val uri = create(treeUri, name)
            write(uri, C2, listOf(null))
            log("BURST $name wrote ${C2.size}B")
        }
        log("done")
    }

    /**
     * Hypothesis probe F: same-doc overwrite paced at the Nextcloud client's
     * upload-turnaround (~1.3s: write -> upload -> local delete). Writes land
     * mid-upload window so the previous local file can be gone when the client
     * reads it -> "local file not exists" -> cancel, and if it's the last write
     * nothing re-lands -> server keeps the pre/cancel state.
     */
    @Test
    fun overwritePacedMidUpload() {
        val treeUri = requireTreeUri()
        val uri = create(treeUri, PROBE_PACED)
        write(uri, C1, listOf(null))
        log("PACED created + first write ${C1.size}B")
        for (i in 0 until PACED_CYCLES) {
            SystemClock.sleep(PACED_PAUSE_MS)
            write(uri, C3, listOf("rwt", "wt", null))
            if (i % 2 == 1) log("PACED cycle=$i wrote ${C3.size}B")
        }
        readBack(uri, "PACED-final", expected = C3)
        log("done")
    }

    /**
     * Hypothesis probe E: rapid in-place overwrites of the SAME doc with NO
     * pause between cycles — the app's real shape (pages/metadata written
     * back-to-back while the Nextcloud client uploader for the previous cycle
     * is still mid-flight). If the in-place rwt leg 0-bytes under this cadence
     * but the paused variant does not, cadence is the differentiator.
     */
    @Test
    fun overwriteRapidNoPause() {
        val treeUri = requireTreeUri()
        val uri = create(treeUri, PROBE_RAPID)
        write(uri, C1, listOf(null))
        log("RAPID created + first write ${C1.size}B")
        for (i in 0 until RAPID_CYCLES) {
            write(uri, C3, listOf("rwt", "wt", null))
            if (i % 4 == 3) log("RAPID cycle=$i wrote ${C3.size}B")
        }
        readBack(uri, "RAPID-final", expected = C3)
        log("done")
    }

    /**
     * Hypothesis probe D: create a 0-byte placeholder (create + close without
     * writing), wait, then fill it in place via openOutputStream("rwt"). If the
     * server keeps the placeholder empty, filling an existing empty doc is a
     * hole.
     */
    @Test
    fun placeholderFillViaRwt() {
        val treeUri = requireTreeUri()
        val uri = create(treeUri, PROBE_PLACEHOLDER)
        val os = context.contentResolver.openOutputStream(uri)
        assertNotNull("openOutputStream null for $PROBE_PLACEHOLDER", os)
        os!!.close()
        log("PLACEHOLDER created + closed without writing")
        SystemClock.sleep(FILL_WAIT_MS)
        write(uri, C2, listOf("rwt", "wt", null))
        log("PLACEHOLDER filled ${C2.size}B via rwt->wt->plain")
        SystemClock.sleep(PAUSE_MS)
        readBack(uri, "PLACEHOLDER-final", expected = C2)
        log("done")
    }

    private fun create(treeUri: Uri, name: String): Uri {
        // On API 30+ the parent must be a document uri built from the tree,
        // not the raw tree uri (the provider rejects the latter as "Invalid URI").
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        val uri = DocumentsContract.createDocument(
            context.contentResolver, parent, "application/octet-stream", name,
        )
        assertNotNull("createDocument returned null for $name", uri)
        return uri!!
    }

    private fun write(docUri: Uri, data: ByteArray, modes: List<String?>) {
        var os: OutputStream? = null
        var usedMode: String? = "none"
        for (mode in modes) {
            os = if (mode == null) {
                context.contentResolver.openOutputStream(docUri)
            } else {
                context.contentResolver.openOutputStream(docUri, mode)
            }
            if (os != null) {
                usedMode = mode
                break
            }
        }
        assertNotNull("openOutputStream null for $docUri modes=$modes", os)
        log("write openOutputStream mode=${usedMode ?: "plain"} for ${docUri.lastPathSegment}")
        os!!.use { it.write(data) }
    }

    private fun readBack(docUri: Uri, label: String, expected: ByteArray?) {
        val bytes = try {
            context.contentResolver.openInputStream(docUri)?.use { it.readBytes() }
        } catch (e: Exception) {
            log("readback $label threw ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val match = when {
            bytes == null -> "null"
            expected == null -> "n/a"
            bytes.contentEquals(expected) -> "match"
            else -> "MISMATCH"
        }
        log("readback $label len=${bytes?.size} md5=${bytes?.let(::md5)} expect=$match")
    }

    private fun requireTreeUri(): Uri {
        val candidates = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission || it.isWritePermission }
            .map { it.uri }
        val nextcloud = candidates.filter { it.authority == NEXTCLOUD_AUTHORITY }
        val tree = (nextcloud + candidates).firstOrNull()
        assertNotNull(
            "No persisted SAF tree grant found (select PicPocketTest via the picker once). " +
                "permissions=$candidates",
            tree,
        )
        return tree!!
    }

    private fun treeUriOrNull(): Uri? {
        return context.contentResolver.persistedUriPermissions
            .firstOrNull { it.uri.authority == NEXTCLOUD_AUTHORITY }
            ?.uri
    }

    private fun deleteQuietly(treeUri: Uri, name: String) {
        try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, name)
            DocumentsContract.deleteDocument(context.contentResolver, doc)
        } catch (_: Exception) {
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, "$msg | t=${SystemClock.elapsedRealtime()}")
    }

    private fun md5(data: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(data)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "SAFProbe"
        private const val NEXTCLOUD_AUTHORITY = "org.nextcloud.documents"
        private const val PROBE_A = "probeA.bin"
        private const val PROBE_B = "probeB.bin"
        private const val PROBE_D = "probeD.bin"
        private const val PROBE_RW = "probeRwt.bin"
        private const val PROBE_RC = "probeRecreate.bin"
        private const val PROBE_PLACEHOLDER = "probePlaceholder.bin"
        private const val PROBE_RAPID = "probeRapid.bin"
        private const val PROBE_PACED = "probePaced.bin"
        private const val PROBE_LOCK_DIR = "probeLock"
        private const val PROBE_LOCK_FILE = "probeLockF"
        private const val PROBE_LOCK_DIR2 = "probeLockY"
        private const val PROBE_LOCK_FILE2 = "probeLockG"
        private const val PAUSE_MS = 2_000L
        private const val FILL_WAIT_MS = 15_000L
        private const val REWRITE_CYCLES = 20
        private const val RAPID_CYCLES = 30
        private const val PACED_CYCLES = 24
        private const val PACED_PAUSE_MS = 1_300L
        private const val BURST_FILES = 50

        private val CROSS_DEVICE_DIRS = listOf(PROBE_LOCK_DIR, PROBE_LOCK_DIR2)
        private val CROSS_DEVICE_FILES = listOf(PROBE_LOCK_FILE, PROBE_LOCK_FILE2)

        private val CLEANUP_NAMES = listOf(
            PROBE_A, PROBE_B, PROBE_D, PROBE_RW, PROBE_RC, PROBE_PLACEHOLDER, PROBE_RAPID, PROBE_PACED,
            PROBE_LOCK_DIR, PROBE_LOCK_FILE, PROBE_LOCK_DIR2, PROBE_LOCK_FILE2,
        ) + List(BURST_FILES) { "burst_%03d.bin".format(it) }

        val C1: ByteArray = ByteArray(131) { (it % 251).toByte() }
        val C2: ByteArray = ByteArray(131) { ((it * 7 + 3) % 251).toByte() }
        val C3: ByteArray = ByteArray(3779) { ((it * 13 + 5) % 251).toByte() }
    }
}
