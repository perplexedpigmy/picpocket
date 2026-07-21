package com.picpocket.app.drive.sync

import android.content.Context
import android.net.Uri
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
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext emptyList()
        root.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { it.name }
    }

    suspend fun listFileNames(treeUri: String, docId: String): List<String> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext emptyList()
        val folder = root.findFile(docId) ?: return@withContext emptyList()
        folder.listFiles()
            .filter { !it.isDirectory }
            .mapNotNull { it.name }
    }

    suspend fun readFile(treeUri: String, docId: String, fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext null
        val folder = root.findFile(docId) ?: return@withContext null
        val file = folder.findFile(fileName) ?: return@withContext null
        val encrypted = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: return@withContext null
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
        root.createDirectory(docId) != null
    }

    suspend fun readMetadataJson(treeUri: String, docId: String): ByteArray? = withContext(Dispatchers.IO) {
        readFile(treeUri, docId, "metadata.json")
    }
}
