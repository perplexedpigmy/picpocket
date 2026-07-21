package com.picpocket.app.drive.sync

import android.content.Context
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.drive.EncryptionManager
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
    private val encryptionManager: EncryptionManager,
    @ApplicationContext private val context: Context,
) {
    private val json = Json { prettyPrint = true }

    suspend fun uploadDocument(doc: StoredDocument): Boolean {
        val docId = doc.id
        val info = localDriveIndex.getDocumentInfo(docId)

        val folderId = if (info.folderId.isNotBlank()) {
            info.folderId
        } else {
            driveFileManager.createOrGetFolder(docId, localDriveIndex.getRootFolderId().ifBlank { null }) ?: return false
        }

        for (page in doc.pages) {
            val pageFile = documentStore.pageFile(docId, page.filename)
            if (pageFile?.exists() == true) {
                val data = pageFile.readBytes()
                val existingId = info.pages[page.filename]
                if (existingId != null) {
                    driveFileManager.deleteFile(existingId)
                }
                val fileId = driveFileManager.uploadFile(folderId, page.filename, data)
                if (fileId != null) {
                    info.pages[page.filename] = fileId
                }
            }
        }

        val metadataJson = json.encodeToString(
            doc.copy(syncVersion = doc.syncVersion + 1, syncTimestamp = System.currentTimeMillis(), syncDirty = false)
        )
        val metadataBytes = metadataJson.toByteArray(Charsets.UTF_8)
        val existingMetadataId = info.pages["metadata.json"]
        if (existingMetadataId != null) {
            driveFileManager.deleteFile(existingMetadataId)
        }
        val metadataFileId = driveFileManager.uploadFile(folderId, "metadata.json", metadataBytes)
        if (metadataFileId != null) {
            info.pages["metadata.json"] = metadataFileId
        }

        info.folderId = folderId
        info.syncVersion = doc.syncVersion + 1
        info.syncTimestamp = System.currentTimeMillis()
        localDriveIndex.setDocumentInfo(docId, info)
        return true
    }

    suspend fun uploadDeletedTombstone(docId: String, deviceId: String) {
        val info = localDriveIndex.getDocumentInfo(docId)
        val folderId = info.folderId
        if (folderId.isBlank()) return

        val tombstoneData = json.encodeToString(
            TombstoneData(deletedAt = System.currentTimeMillis(), byDevice = deviceId, acknowledgedBy = listOf(deviceId))
        ).toByteArray(Charsets.UTF_8)

        val existingTombstoneId = info.pages[".deleted"]
        if (existingTombstoneId != null) {
            driveFileManager.deleteFile(existingTombstoneId)
        }
        driveFileManager.uploadFile(folderId, ".deleted", tombstoneData)
    }
}

@Serializable
data class TombstoneData(
    val deletedAt: Long,
    val byDevice: String,
    val acknowledgedBy: List<String>,
)
