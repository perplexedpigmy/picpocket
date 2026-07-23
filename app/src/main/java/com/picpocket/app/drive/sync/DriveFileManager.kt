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
import kotlinx.coroutines.withContext
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton

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

    companion object {
        private const val TAG = "DriveFileManager"
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
        try { context.contentResolver.refresh(root.uri, null, null) } catch (_: Exception) { }
        root.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { folder ->
                val name = folder.name ?: return@mapNotNull null
                try { context.contentResolver.refresh(folder.uri, null, null) } catch (_: Exception) { }
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
        } catch (_: Exception) { }
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
            val encrypted = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            if (encrypted == null) { Tracing.w(Category.DRIVE_FILES, TAG, "readFile: inputStream null for $docId/$fileName"); return@withContext null }
            return@withContext try { encryptionManager.decrypt(encrypted) } catch (_: AEADBadTagException) { encrypted }
        }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        if (root == null) { Tracing.w(Category.DRIVE_FILES, TAG, "readFile: root null for $docId/$fileName"); return@withContext null }
        val folder = root.findFile(docId)
        if (folder == null) { Tracing.w(Category.DRIVE_FILES, TAG, "readFile: folder not found for $docId/$fileName"); return@withContext null }
        val file = folder.findFile(fileName)
        if (file == null) { Tracing.w(Category.DRIVE_FILES, TAG, "readFile: file not found for $docId/$fileName"); return@withContext null }
        val encrypted = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        if (encrypted == null) { Tracing.w(Category.DRIVE_FILES, TAG, "readFile: inputStream null for $docId/$fileName"); return@withContext null }
        try {
            encryptionManager.decrypt(encrypted)
        } catch (_: AEADBadTagException) {
            encrypted
        }
    }

    suspend fun writeFile(treeUri: String, docId: String, fileName: String, data: ByteArray, mimeType: String = "application/octet-stream"): Boolean = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext false
        val encrypted = encryptionManager.encrypt(data)
        val folder = root.findFile(docId)
        val docFolder = folder ?: root.createDirectory(docId) ?: return@withContext false
        try {
            context.contentResolver.refresh(docFolder.uri, null, null)
        } catch (_: Exception) { }
        for (child in docFolder.listFiles()) {
            if (child.name == fileName) {
                child.delete()
            }
        }
        val newFile = docFolder.createFile(mimeType, fileName) ?: return@withContext false
        context.contentResolver.openOutputStream(newFile.uri)?.use { it.write(encrypted) } ?: return@withContext false
        true
    }

    suspend fun deleteFileByName(treeUri: String, docId: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext false
        val folder = root.findFile(docId) ?: return@withContext false
        try {
            context.contentResolver.refresh(folder.uri, null, null)
        } catch (_: Exception) { }
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
        } catch (_: Exception) { }
        for (child in root.listFiles()) {
            if (child.name == docId && child.isDirectory) return@withContext true
        }
        root.createDirectory(docId) != null
    }

    suspend fun readMetadataJson(
        treeUri: String, docId: String,
        remoteCache: Map<String, List<DocumentFile>>? = null,
    ): ByteArray? = withContext(Dispatchers.IO) {
        readFile(treeUri, docId, "metadata.json", remoteCache)
    }
}
