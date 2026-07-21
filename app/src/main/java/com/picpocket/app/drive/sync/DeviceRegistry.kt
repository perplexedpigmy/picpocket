package com.picpocket.app.drive.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.StoredDocument
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val orphans = mutableListOf<OrphanedDocument>()

    fun getOrphans(): List<OrphanedDocument> = orphans.filter { !it.acknowledged }

    suspend fun detectOrphans(localDocs: List<StoredDocument>, remoteDocs: List<DownloadEngine.RemoteDocument>) {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return
        orphans.clear()
        val devices = localDriveIndex.getDevices()

        for (remote in remoteDocs) {
            if (!remote.isDeleted) continue
            val localDoc = localDocs.find { it.id == remote.docId }
            if (localDoc == null) continue

            val byDevice = if (".deleted" in remote.fileNames) {
                val data = driveFileManager.readFile(treeUri, remote.docId, ".deleted")
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
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isNotBlank()) {
            driveFileManager.deleteFileByName(treeUri, docId, ".deleted")
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
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return
        val data = driveFileManager.readFile(treeUri, docId, ".deleted") ?: return
        val tombstone = try {
            json.decodeFromString<TombstoneData>(String(data, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val deviceId = localDriveIndex.getLocalDeviceId()
        if (deviceId !in tombstone.acknowledgedBy) {
            val updated = tombstone.copy(acknowledgedBy = tombstone.acknowledgedBy + deviceId)
            driveFileManager.writeFile(
                treeUri, docId, ".deleted",
                json.encodeToString(updated).toByteArray(Charsets.UTF_8),
            )
        }
    }

    suspend fun cleanDrive() {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return
        val allDeviceIds = localDriveIndex.getAllKnownDeviceIds()
        if (allDeviceIds.isEmpty()) return

        val docIds = driveFileManager.listDocFolders(treeUri)

        for (docId in docIds) {
            val fileNames = driveFileManager.listFileNames(treeUri, docId)
            if (".deleted" !in fileNames) continue
            val data = driveFileManager.readFile(treeUri, docId, ".deleted") ?: continue
            val tombstone = try {
                json.decodeFromString<TombstoneData>(String(data, Charsets.UTF_8))
            } catch (_: Exception) {
                continue
            }

            if (allDeviceIds.all { it in tombstone.acknowledgedBy }) {
                driveFileManager.deleteFileByName(treeUri, docId, ".deleted")
                val remaining = driveFileManager.listFileNames(treeUri, docId)
                if (remaining.isEmpty()) {
                    val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                    root?.findFile(docId)?.delete()
                }
            }
        }
    }
}
