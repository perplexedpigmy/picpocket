package com.picpocket.app.drive.sync

import android.content.Context
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.StoredDocument

import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadEngine @Inject constructor(
    private val driveFileManager: DriveFileManager,
    private val documentStore: DocumentStore,
    private val localDriveIndex: LocalDriveIndex,
    @ApplicationContext private val context: Context,
) {
    private val json = Json { prettyPrint = true }

    private fun treeUri(): String? {
        val uri = localDriveIndex.getRootTreeUri()
        return uri.ifBlank { null }
    }

    suspend fun ensureFolder(docId: String): Boolean {
        val tree = treeUri() ?: return false
        return driveFileManager.createDocFolder(tree, docId)
    }

    suspend fun uploadSinglePage(docId: String, pageFilename: String, data: ByteArray): Boolean {
        val tree = treeUri() ?: return false
        return driveFileManager.writeFile(tree, docId, pageFilename, data)
    }

    suspend fun uploadMetadataBytes(docId: String, data: ByteArray): Boolean {
        val tree = treeUri() ?: return false
        return driveFileManager.writeFile(tree, docId, "metadata.json", data)
    }

    suspend fun uploadDocument(doc: StoredDocument): Boolean {
        val docId = doc.id
        if (!ensureFolder(docId)) return false
        val info = localDriveIndex.getDocumentInfo(docId)

        for (page in doc.pages) {
            val pageFile = documentStore.pageFile(docId, page.filename)
            if (pageFile.exists()) {
                uploadSinglePage(docId, page.filename, pageFile.readBytes())
            }
        }

        val syncDoc = doc.copy(
            syncVersion = doc.syncVersion + 1,
            syncTimestamp = System.currentTimeMillis(),
        )
        val metadataBytes = json.encodeToString(syncDoc).toByteArray(Charsets.UTF_8)
        uploadMetadataBytes(docId, metadataBytes)

        info.syncVersion = syncDoc.syncVersion
        info.syncTimestamp = syncDoc.syncTimestamp
        localDriveIndex.setDocumentInfo(docId, info)
        return true
    }

    suspend fun uploadPage(docId: String, pageNumber: Int): Result<Unit> {
        if (!ensureFolder(docId)) return Result.failure(Exception("Failed to create folder"))
        val doc = documentStore.readMetadata(docId).getOrElse { return Result.failure(it) }
        val page = doc.pages.find { it.pageNumber == pageNumber }
            ?: return Result.failure(Exception("Page $pageNumber not found"))
        val pageFile = documentStore.pageFile(docId, page.filename)
        if (pageFile.exists() != true) return Result.failure(Exception("Page file not found"))
        uploadSinglePage(docId, page.filename, pageFile.readBytes())
        return Result.success(Unit)
    }

    suspend fun deletePage(docId: String, pageNumber: Int): Result<Unit> {
        val tree = treeUri() ?: return Result.success(Unit)
        val doc = documentStore.readMetadata(docId).getOrElse { return Result.failure(it) }
        val page = doc.pages.find { it.pageNumber == pageNumber }
        val filename = page?.filename ?: documentStore.filenameForPage(pageNumber)
        driveFileManager.deleteFileByName(tree, docId, filename)
        return Result.success(Unit)
    }

    suspend fun replacePageImage(docId: String, pageNumber: Int): Result<Unit> {
        if (!ensureFolder(docId)) return Result.failure(Exception("Failed to create folder"))
        val doc = documentStore.readMetadata(docId).getOrElse { return Result.failure(it) }
        val page = doc.pages.find { it.pageNumber == pageNumber }
            ?: return Result.failure(Exception("Page $pageNumber not found"))
        val pageFile = documentStore.pageFile(docId, page.filename)
        if (pageFile.exists() != true) return Result.failure(Exception("Page file not found"))
        uploadSinglePage(docId, page.filename, pageFile.readBytes())
        return Result.success(Unit)
    }

    suspend fun updateMetadata(docId: String): Result<Unit> {
        val tree = treeUri() ?: return Result.failure(Exception("No folder selected"))
        val doc = documentStore.readMetadata(docId).getOrElse { return Result.failure(it) }
        val metadataBytes = json.encodeToString(doc).toByteArray(Charsets.UTF_8)
        driveFileManager.writeFile(tree, docId, "metadata.json", metadataBytes)
        return Result.success(Unit)
    }

    suspend fun replacePages(docId: String, keptFilenames: List<String>): Result<List<String>> {
        val tree = treeUri() ?: return Result.failure(Exception("No folder selected"))
        val doc = documentStore.readMetadata(docId).getOrElse { return Result.failure(it) }
        val removed = doc.pages.filter { it.filename !in keptFilenames }

        for (page in removed) {
            driveFileManager.deleteFileByName(tree, docId, page.filename)
        }
        return Result.success(removed.map { it.filename })
    }

    suspend fun reEncryptDocument(docId: String): Result<Unit> {
        if (!ensureFolder(docId)) return Result.failure(Exception("Failed to create folder"))
        val doc = documentStore.readMetadata(docId).getOrElse { return Result.failure(it) }

        for (page in doc.pages) {
            val pageFile = documentStore.pageFile(docId, page.filename)
            if (pageFile.exists() == true) {
                uploadSinglePage(docId, page.filename, pageFile.readBytes())
            }
        }

        val updatedDoc = doc.copy(syncVersion = doc.syncVersion + 1)
        val metadataBytes = json.encodeToString(updatedDoc).toByteArray(Charsets.UTF_8)
        uploadMetadataBytes(docId, metadataBytes)

        val info = localDriveIndex.getDocumentInfo(docId)
        info.syncVersion = updatedDoc.syncVersion
        localDriveIndex.setDocumentInfo(docId, info)
        return Result.success(Unit)
    }

    suspend fun uploadDeletedTombstone(docId: String, deviceId: String) {
        val tree = treeUri() ?: return

        val tombstoneData = json.encodeToString(
            TombstoneData(deletedAt = System.currentTimeMillis(), byDevice = deviceId, acknowledgedBy = listOf(deviceId))
        ).toByteArray(Charsets.UTF_8)

        driveFileManager.writeFile(tree, docId, ".deleted", tombstoneData)
    }
}

@Serializable
data class TombstoneData(
    val deletedAt: Long,
    val byDevice: String,
    val acknowledgedBy: List<String>,
)
