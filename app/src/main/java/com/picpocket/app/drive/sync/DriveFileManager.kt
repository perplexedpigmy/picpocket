package com.picpocket.app.drive.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.picpocket.app.drive.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            Log.d(TAG, "listDocFolders: childrenUri=$childrenUri")
            val cursor = context.contentResolver.query(
                childrenUri, null, null, null, null,
            )
            if (cursor == null) {
                Log.w(TAG, "listDocFolders: cursor null")
                return@withContext emptyList()
            }
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            Log.d(TAG, "listDocFolders: nameIdx=$nameIdx mimeIdx=$mimeIdx rowCount=${cursor.count}")
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else "?"
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else "?"
                Log.d(TAG, "listDocFolders: name=$name mime=$mime")
                if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                    names.add(name)
                }
            }
            cursor.close()
            Log.d(TAG, "listDocFolders: found ${names.size} folders: $names")
            names
        } catch (e: Exception) {
            Log.e(TAG, "listDocFolders: exception=${e.message}", e)
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

    suspend fun listFileNames(treeUri: String, docId: String): List<String> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        if (root == null) { Log.w(TAG, "listFileNames: root null for $docId"); return@withContext emptyList() }
        val folder = root.findFile(docId)
        if (folder == null) { Log.w(TAG, "listFileNames: folder not found for docId=$docId"); return@withContext emptyList() }
        try {
            context.contentResolver.refresh(folder.uri, null, null)
        } catch (_: Exception) { }
        val rawFiles = folder.listFiles()
        Log.d(TAG, "listFileNames: docId=$docId rawCount=${rawFiles.size} total")
        for (f in rawFiles) {
            Log.d(TAG, "listFileNames:   entry name=${f.name} dir=${f.isDirectory} uri=${f.uri}")
        }
        val names = rawFiles.filter { !it.isDirectory }.mapNotNull { it.name }
        Log.d(TAG, "listFileNames: docId=$docId filtered=${names.toList()}")
        names
    }

    suspend fun readFile(treeUri: String, docId: String, fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        if (root == null) { Log.w(TAG, "readFile: root null for $docId/$fileName"); return@withContext null }
        val folder = root.findFile(docId)
        if (folder == null) { Log.w(TAG, "readFile: folder not found for $docId/$fileName"); return@withContext null }
        val file = folder.findFile(fileName)
        if (file == null) { Log.w(TAG, "readFile: file not found for $docId/$fileName"); return@withContext null }
        val encrypted = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        if (encrypted == null) { Log.w(TAG, "readFile: inputStream null for $docId/$fileName"); return@withContext null }
        encryptionManager.decrypt(encrypted)
    }

    suspend fun writeFile(treeUri: String, docId: String, fileName: String, data: ByteArray, mimeType: String = "application/octet-stream"): Boolean = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext false
        val encrypted = encryptionManager.encrypt(data)
        val folder = root.findFile(docId)
        val docFolder = folder ?: root.createDirectory(docId) ?: return@withContext false
        val existing = docFolder.findFile(fileName)
        if (existing != null) {
            existing.delete()
        }
        val newFile = docFolder.createFile(mimeType, fileName) ?: return@withContext false
        context.contentResolver.openOutputStream(newFile.uri)?.use { it.write(encrypted) } ?: return@withContext false
        true
    }

    suspend fun deleteFileByName(treeUri: String, docId: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext false
        val folder = root.findFile(docId) ?: return@withContext false
        val file = folder.findFile(fileName) ?: return@withContext false
        file.delete()
    }

    suspend fun createDocFolder(treeUri: String, docId: String): Boolean = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext false
        val existing = root.findFile(docId)
        if (existing != null && existing.isDirectory) return@withContext true
        if (existing == null) {
            root.createDirectory(docId) != null
        } else {
            existing.delete()
            root.createDirectory(docId) != null
        }
    }

    suspend fun readMetadataJson(treeUri: String, docId: String): ByteArray? = withContext(Dispatchers.IO) {
        readFile(treeUri, docId, "metadata.json")
    }
}
