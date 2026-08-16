package com.picpocket.app.drive.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.picpocket.app.debug.Category
import com.picpocket.app.debug.Tracing
import androidx.documentfile.provider.DocumentFile
import com.picpocket.app.drive.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface WriteOutcome {
    object Verified : WriteOutcome
    data class Failed(val reason: String) : WriteOutcome
}

@Singleton
class DriveFileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: EncryptionManager,
) {

    suspend fun listDocFolders(treeUri: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(treeUri)
            val docId = extractDocumentId(uri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
            Tracing.d(Category.DRIVE_FILES, TAG, "listDocFolders: childrenUri=$childrenUri")
            val cursor = context.contentResolver.query(
                childrenUri, null, null, null, null,
            )
            if (cursor == null) {
                Tracing.w(Category.DRIVE_FILES, TAG, "listDocFolders: cursor null")
                return@withContext emptyList()
            }
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            Tracing.d(Category.DRIVE_FILES, TAG, "listDocFolders: nameIdx=$nameIdx mimeIdx=$mimeIdx rowCount=${cursor.count}")
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else "?"
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else "?"
                Tracing.d(Category.DRIVE_FILES, TAG, "listDocFolders: name=$name mime=$mime")
                if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                    names.add(name)
                }
            }
            cursor.close()
            Tracing.d(Category.DRIVE_FILES, TAG, "listDocFolders: found ${names.size} folders: $names")
            names
        } catch (e: Exception) {
            Tracing.e(Category.DRIVE_FILES, TAG, "listDocFolders: exception=${e.message}", e)
            emptyList()
        }
    }

    private fun extractDocumentId(uri: Uri): String {
        return try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Throwable) {
            DocumentsContract.getDocumentId(uri)
        }
    }

    suspend fun prefetchRemoteFiles(treeUri: String): Map<String, List<DocumentFile>> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext emptyMap()
        try { context.contentResolver.refresh(root.uri, null, null) } catch (_: Throwable) { }
        root.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { folder ->
                val name = folder.name ?: return@mapNotNull null
                try { context.contentResolver.refresh(folder.uri, null, null) } catch (_: Throwable) { }
                name to folder.listFiles().toList()
            }
            .toMap()
    }

    suspend fun listFileNames(
        treeUri: String, docId: String,
        remoteCache: Map<String, List<DocumentFile>>? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        val cached = remoteCache?.get(docId)
        if (cached != null) {
            return@withContext cached.filter { !it.isDirectory }.mapNotNull { it.name }
        }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        if (root == null) { Tracing.w(Category.DRIVE_FILES, TAG, "listFileNames: root null for $docId"); return@withContext emptyList() }
        val folder = root.findFile(docId)
        if (folder == null) { Tracing.w(Category.DRIVE_FILES, TAG, "listFileNames: folder not found for docId=$docId"); return@withContext emptyList() }
        try {
            context.contentResolver.refresh(folder.uri, null, null)
        } catch (_: Throwable) { }
        val rawFiles = folder.listFiles()
        Tracing.d(Category.DRIVE_FILES, TAG, "listFileNames: docId=$docId rawCount=${rawFiles.size} total")
        for (f in rawFiles) {
            Tracing.d(Category.DRIVE_FILES, TAG, "listFileNames:   entry name=${f.name} dir=${f.isDirectory} uri=${f.uri}")
        }
        val names = rawFiles.filter { !it.isDirectory }.mapNotNull { it.name }
        Tracing.d(Category.DRIVE_FILES, TAG, "listFileNames: docId=$docId filtered=${names.toList()}")
        names
    }

    suspend fun readFile(
        treeUri: String, docId: String, fileName: String,
        remoteCache: Map<String, List<DocumentFile>>? = null,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val cached = remoteCache?.get(docId)
        if (cached != null) {
            val file = cached.find { it.name == fileName && !it.isDirectory }
            if (file == null) { Tracing.w(Category.DRIVE_FILES, TAG, "readFile: file not found (cache) for $docId/$fileName"); return@withContext null }
            val encrypted = readBytesGuarded(file.uri)
            if (encrypted == null) { Tracing.w(Category.DRIVE_FILES, TAG, "readFile: inputStream null for $docId/$fileName"); return@withContext null }
            return@withContext try { encryptionManager.decrypt(encrypted) } catch (_: AEADBadTagException) { encrypted }
        }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        if (root == null) { Tracing.w(Category.DRIVE_FILES, TAG, "readFile: root null for $docId/$fileName"); return@withContext null }
        val folder = root.findFile(docId)
        if (folder == null) { Tracing.w(Category.DRIVE_FILES, TAG, "readFile: folder not found for $docId/$fileName"); return@withContext null }
        val file = folder.findFile(fileName)
        if (file == null) { Tracing.w(Category.DRIVE_FILES, TAG, "readFile: file not found for $docId/$fileName"); return@withContext null }
        val encrypted = readBytesGuarded(file.uri)
        if (encrypted == null) { Tracing.w(Category.DRIVE_FILES, TAG, "readFile: read aborted/empty for $docId/$fileName"); return@withContext null }
        try {
            encryptionManager.decrypt(encrypted)
        } catch (_: AEADBadTagException) {
            encrypted
        }
    }

    suspend fun writeFile(
        treeUri: String, docId: String, fileName: String, data: ByteArray,
        mimeType: String = "application/octet-stream",
    ): WriteOutcome = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: return@withContext WriteOutcome.Failed("tree uri invalid for $docId/$fileName")
        val encrypted = encryptionManager.encrypt(data)
        val folder = root.findFile(docId)
        val docFolder = folder ?: root.createDirectory(docId)
            ?: return@withContext WriteOutcome.Failed("failed to create doc folder $docId")
        writeFileTo(docFolder, fileName, encrypted, mimeType)
    }

    suspend fun writeRootFile(
        treeUri: String, fileName: String, data: ByteArray,
        mimeType: String = "application/json",
    ): WriteOutcome = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: return@withContext WriteOutcome.Failed("tree uri invalid for $fileName")
        writeFileTo(root, fileName, data, mimeType)
    }

    private suspend fun writeFileTo(
        parent: DocumentFile, fileName: String, data: ByteArray, mimeType: String,
    ): WriteOutcome {
        var lastFailure: String? = null
        for (attempt in 1..WRITE_ATTEMPTS) {
            try {
                context.contentResolver.refresh(parent.uri, null, null)
            } catch (_: Throwable) { }
            val existing = try {
                parent.findFile(fileName)
            } catch (_: Exception) {
                null
            }
            val target = existing ?: try {
                parent.createFile(mimeType, fileName)
            } catch (_: Exception) {
                null
            }
            if (target == null) {
                lastFailure = "createFile failed for $fileName"
                Tracing.w(Category.DRIVE_FILES, TAG, "writeFileTo: attempt $attempt createFile failed for $fileName, verifying existing target")
                val outcome = verifySettled(parent, fileName, data.size)
                if (outcome is WriteOutcome.Verified) return outcome
                lastFailure = (outcome as WriteOutcome.Failed).reason
            } else {
                val os = openOutputStreamGuarded(target.uri, existing != null)
                if (os == null) {
                    lastFailure = "openOutputStream null for $fileName"
                    Tracing.w(Category.DRIVE_FILES, TAG, "writeFileTo: attempt $attempt openOutputStream null for $fileName")
                } else {
                    var writeFailed = false
                    try {
                        withTimeout(STALL_GUARD_MS) {
                            runInterruptible { os.write(data) }
                        }
                    } catch (e: Exception) {
                        writeFailed = true
                        lastFailure = "write phase aborted (${e.javaClass.simpleName}) for $fileName"
                        Tracing.w(Category.DRIVE_FILES, TAG, "writeFileTo: attempt $attempt write phase aborted (${e.javaClass.simpleName}) for $fileName")
                    } finally {
                        try {
                            withTimeout(CLOSE_GUARD_MS) {
                                runInterruptible { os.close() }
                            }
                        } catch (_: Exception) {
                            Tracing.w(Category.DRIVE_FILES, TAG, "writeFileTo: attempt $attempt close aborted for $fileName")
                        }
                    }
                    if (!writeFailed) {
                        val outcome = verifySettled(parent, fileName, data.size)
                        if (outcome is WriteOutcome.Verified) return outcome
                        lastFailure = (outcome as WriteOutcome.Failed).reason
                    }
                }
            }
            if (attempt < WRITE_ATTEMPTS) {
                Tracing.w(Category.DRIVE_FILES, TAG, "writeFileTo: attempt $attempt failed ($lastFailure), retrying in ${WRITE_RETRY_DELAY_MS}ms")
                delay(WRITE_RETRY_DELAY_MS)
            }
        }
        Tracing.w(Category.DRIVE_FILES, TAG, "writeFileTo: all $WRITE_ATTEMPTS attempts failed for $fileName, rolling back")
        rollbackFile(parent, fileName)
        return WriteOutcome.Failed(lastFailure ?: "write failed after $WRITE_ATTEMPTS attempts for $fileName")
    }

    private fun openOutputStreamGuarded(uri: Uri, existing: Boolean): OutputStream? {
        if (existing) {
            try {
                return context.contentResolver.openOutputStream(uri, "rwt")
            } catch (_: Exception) { }
            try {
                return context.contentResolver.openOutputStream(uri, "wt")
            } catch (_: Exception) { }
        }
        return try {
            context.contentResolver.openOutputStream(uri)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun rollbackFile(parent: DocumentFile, fileName: String) {
        try {
            context.contentResolver.refresh(parent.uri, null, null)
        } catch (_: Throwable) { }
        val target = try {
            parent.findFile(fileName)
        } catch (_: Exception) {
            null
        }
        if (target == null) {
            Tracing.w(Category.DRIVE_FILES, TAG, "rollbackFile: $fileName already gone, nothing to delete")
            return
        }
        val deleted = try {
            target.delete()
        } catch (_: Exception) {
            false
        }
        Tracing.w(
            Category.DRIVE_FILES, TAG,
            if (deleted) "rollbackFile: deleted $fileName after failed write"
            else "rollbackFile: could not delete $fileName after failed write",
        )
    }

    private suspend fun verifySettled(docFolder: DocumentFile, fileName: String, expectedSize: Int): WriteOutcome {
        val deadline = System.currentTimeMillis() + RECONCILE_WINDOW_MS
        var lastActual: Long? = null
        while (true) {
            lastActual = settledSize(docFolder, fileName)
            if (verifyOutcome(expectedSize, lastActual) is WriteOutcome.Verified) {
                Tracing.d(Category.DRIVE_FILES, TAG, "verifySettled: verified $fileName size=$lastActual")
                return WriteOutcome.Verified
            }
            if (System.currentTimeMillis() >= deadline) break
            delay(VERIFY_POLL_INTERVAL_MS)
        }
        return verifyOutcome(expectedSize, lastActual)
    }

    private suspend fun settledSize(docFolder: DocumentFile, fileName: String): Long? {
        try {
            context.contentResolver.refresh(docFolder.uri, null, null)
        } catch (_: Throwable) { }
        val file = docFolder.findFile(fileName) ?: return null
        return file.length()
    }

    private suspend fun readBytesGuarded(uri: Uri): ByteArray? {
        // openInputStream throws (e.g. FileNotFoundException from the bridge's
        // DocumentsProvider) when the cached folder listing is stale and the
        // file was deleted server-side. Treat that as "unreadable" (null) so a
        // single stale bridge listing aborts the sync attempt instead of the
        // whole performSync; the next sync's refresh converges the listing.
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Tracing.w(Category.DRIVE_FILES, TAG, "readBytesGuarded: open failed (${e.javaClass.simpleName})")
            null
        } ?: return null
        return try {
            withTimeout(READ_GUARD_MS) {
                runInterruptible { stream.use { it.readBytes() } }
            }
        } catch (e: Exception) {
            Tracing.w(Category.DRIVE_FILES, TAG, "readBytesGuarded: read aborted (${e.javaClass.simpleName})")
            null
        }
    }

    companion object {
        private const val TAG = "DriveFileManager"
        const val STALL_GUARD_MS = 60_000L
        const val CLOSE_GUARD_MS = 10_000L
        const val RECONCILE_WINDOW_MS = 30_000L
        const val VERIFY_POLL_INTERVAL_MS = 2_000L
        const val READ_GUARD_MS = 60_000L
        const val WRITE_ATTEMPTS = 3
        const val WRITE_RETRY_DELAY_MS = 500L

        fun verifyOutcome(expectedSize: Int, actualSize: Long?): WriteOutcome {
            return if (actualSize == expectedSize.toLong()) {
                WriteOutcome.Verified
            } else {
                WriteOutcome.Failed("write not settled: expected=$expectedSize actual=$actualSize")
            }
        }
    }

    suspend fun deleteFileByName(treeUri: String, docId: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext false
        val folder = root.findFile(docId) ?: return@withContext false
        try {
            context.contentResolver.refresh(folder.uri, null, null)
        } catch (_: Throwable) { }
        for (child in folder.listFiles()) {
            if (child.name == fileName) {
                return@withContext child.delete()
            }
        }
        false
    }

    suspend fun createDocFolder(treeUri: String, docId: String): Boolean = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext false
        try {
            context.contentResolver.refresh(root.uri, null, null)
        } catch (_: Throwable) { }
        for (child in root.listFiles()) {
            if (child.name == docId && child.isDirectory) return@withContext true
        }
        root.createDirectory(docId) != null
    }

    suspend fun readMetadataJson(
        treeUri: String, docId: String, fileName: String,
        remoteCache: Map<String, List<DocumentFile>>? = null,
    ): ByteArray? = withContext(Dispatchers.IO) {
        readFile(treeUri, docId, fileName, remoteCache)
    }
}
