package com.docscanner.drive.sync

import com.docscanner.data.store.DocumentStore
import com.docscanner.data.store.StoredDocument
import com.google.api.services.drive.model.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class OrphanedDocument(
    val docId: String,
    val name: String,
    val deletingDeviceName: String,
    val deletedAt: Long,
    val pageCount: Int,
    var acknowledged: Boolean = false,
)

@Singleton
class DeviceRegistry @Inject constructor(
    private val driveFileManager: DriveFileManager,
    private val documentStore: DocumentStore,
    private val localDriveIndex: LocalDriveIndex,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val orphans = mutableListOf<OrphanedDocument>()

    fun getOrphans(): List<OrphanedDocument> = orphans.filter { !it.acknowledged }

    suspend fun detectOrphans(localDocs: List<StoredDocument>, remoteDocs: List<DownloadEngine.RemoteDocument>) {
        orphans.clear()
        val devices = localDriveIndex.getDevices()

        for (remote in remoteDocs) {
            if (!remote.isDeleted) continue
            val localDoc = localDocs.find { it.id == remote.docId }
            if (localDoc == null) continue

            val tombstoneId = remote.files[".deleted"]
            val byDevice = if (tombstoneId != null) {
                val data = driveFileManager.downloadFile(tombstoneId)
                if (data != null) {
                    try {
                        json.decodeFromString<TombstoneData>(String(data, Charsets.UTF_8))
                    } catch (_: Exception) {
                        null
                    }
                } else null
            } else null

            val deletingDeviceName = if (byDevice != null) {
                devices[byDevice.byDevice]?.name ?: byDevice.byDevice
            } else "Unknown device"

            orphans.add(
                OrphanedDocument(
                    docId = remote.docId,
                    name = localDoc.name,
                    deletingDeviceName = deletingDeviceName,
                    deletedAt = byDevice?.deletedAt ?: 0L,
                    pageCount = localDoc.pages.size,
                ),
            )
        }
    }

    suspend fun keepOrphan(docId: String) {
        val info = localDriveIndex.getDocumentInfo(docId)
        if (info.folderId.isNotBlank()) {
            driveFileManager.deleteFile("${info.folderId}/.deleted")
        }
        val doc = documentStore.readMetadata(docId).getOrNull() ?: return
        val updated = doc.copy(syncDirty = true, syncVersion = 0, syncTimestamp = System.currentTimeMillis())
        documentStore.writeMetadata(docId, updated)
        val orphan = orphans.find { it.docId == docId }
        orphan?.acknowledged = true
    }

    suspend fun deleteOrphanLocally(docId: String) {
        documentStore.deleteDocument(docId)
        acknowledgeTombstone(docId)
        val orphan = orphans.find { it.docId == docId }
        orphan?.acknowledged = true
    }

    suspend fun dismissOrphan(docId: String) {
        acknowledgeTombstone(docId)
        val orphan = orphans.find { it.docId == docId }
        orphan?.acknowledged = true
    }

    private suspend fun acknowledgeTombstone(docId: String) {
        val info = localDriveIndex.getDocumentInfo(docId)
        if (info.folderId.isBlank()) return
        val files = driveFileManager.findFilesInFolder(info.folderId)
        val tombstoneFile = files.find { it.name == ".deleted" } ?: return
        val data = driveFileManager.downloadFile(tombstoneFile.id) ?: return
        val tombstone = try {
            json.decodeFromString<TombstoneData>(String(data, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val deviceId = localDriveIndex.getLocalDeviceId()
        if (deviceId !in tombstone.acknowledgedBy) {
            val updated = tombstone.copy(acknowledgedBy = tombstone.acknowledgedBy + deviceId)
            driveFileManager.uploadFile(
                info.folderId,
                ".deleted",
                json.encodeToString(updated).toByteArray(Charsets.UTF_8),
            )
        }
    }

    suspend fun cleanDrive() {
        val allDeviceIds = localDriveIndex.getAllKnownDeviceIds()
        if (allDeviceIds.isEmpty()) return
        val allFolders = driveFileManager.listAllFolders()

        for (folder in allFolders) {
            val files = driveFileManager.findFilesInFolder(folder.id)
            val tombstoneFile = files.find { it.name == ".deleted" } ?: continue
            val data = driveFileManager.downloadFile(tombstoneFile.id) ?: continue
            val tombstone = try {
                json.decodeFromString<TombstoneData>(String(data, Charsets.UTF_8))
            } catch (_: Exception) {
                continue
            }

            if (allDeviceIds.all { it in tombstone.acknowledgedBy }) {
                driveFileManager.deleteFile(tombstoneFile.id)
                val remainingFiles = driveFileManager.findFilesInFolder(folder.id)
                if (remainingFiles.isEmpty()) {
                    driveFileManager.deleteFile(folder.id)
                }
            }
        }
    }
}
