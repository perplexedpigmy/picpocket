package com.picpocket.app.drive.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
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
    val isOwnDeletion: Boolean = false,
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
    var remoteEncrypted: Boolean = false
        private set

    fun getOrphans(): List<OrphanedDocument> = orphans.filter { !it.acknowledged }

    fun getMyDeleted(): List<OrphanedDocument> = getOrphans().filter { it.isOwnDeletion }

    fun getOthersDeleted(): List<OrphanedDocument> = getOrphans().filter { !it.isOwnDeletion }

    suspend fun detectOrphans(
        localDocs: List<StoredDocument>,
        remoteDocs: List<DownloadEngine.RemoteDocument>,
        remoteCache: Map<String, List<DocumentFile>>? = null,
    ) {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return
        orphans.clear()
        val devices = localDriveIndex.getDevices()

        for (remote in remoteDocs) {
            if (!remote.isDeleted) continue
            val localDoc = localDocs.find { it.id == remote.docId }
            if (localDoc == null) continue

            val byDevice = if (".deleted" in remote.fileNames) {
                val data = driveFileManager.readFile(treeUri, remote.docId, ".deleted", remoteCache)
                if (data != null) {
                    try {
                        json.decodeFromString<TombstoneData>(String(data, Charsets.UTF_8))
                    } catch (_: Exception) {
                        null
                    }
                } else null
            } else null

            val localDeviceId = localDriveIndex.getLocalDeviceId()
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
                    isOwnDeletion = byDevice?.byDevice == localDeviceId,
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
        val updated = doc.copy(syncVersion = 0, syncTimestamp = System.currentTimeMillis())
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

    fun getDeviceName(): String {
        val systemName = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) { null }
        return if (!systemName.isNullOrBlank()) systemName else Build.MODEL
    }

    suspend fun syncRegistryToDrive(encrypted: Boolean = false) {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return
        val deviceId = localDriveIndex.getLocalDeviceId()
        if (deviceId.isBlank()) return
        localDriveIndex.setDevice(
            deviceId,
            localDriveIndex.getDevices()[deviceId]?.copy(
                name = getDeviceName(),
                lastSeen = System.currentTimeMillis(),
            ) ?: DeviceInfo(
                name = getDeviceName(),
                firstSeen = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
            ),
        )
        val registry = SharedDeviceRegistry(
            encrypted = encrypted,
            devices = localDriveIndex.getDevices().map { (id, info) ->
                SharedDevice(id = id, name = info.name, lastSeen = info.lastSeen)
            },
        )
        val data = json.encodeToString(registry).toByteArray(Charsets.UTF_8)
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        if (root == null) { Log.w(TAG, "syncRegistryToDrive: root null"); return }
        try {
            context.contentResolver.refresh(root.uri, null, null)
        } catch (_: Exception) { }
        for (child in root.listFiles()) {
            if (child.name == REGISTRY_FILE) {
                child.delete()
            }
        }
        val created = root.createFile("application/json", REGISTRY_FILE)
        Log.d(TAG, "syncRegistryToDrive: created=$created")
        if (created != null) {
            context.contentResolver.openOutputStream(created.uri)?.use { it.write(data) }
        }
    }

    suspend fun syncRegistryFromDrive() {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        if (root == null) { Log.w(TAG, "syncRegistryFromDrive: root null"); return }
        try { context.contentResolver.refresh(root.uri, null, null) } catch (_: Exception) { }
        val file = root.listFiles().find { it.name == REGISTRY_FILE }
        Log.d(TAG, "syncRegistryFromDrive: file=$file")
        if (file == null) { Log.w(TAG, "syncRegistryFromDrive: $REGISTRY_FILE not found"); return }
        val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: return
        val remote = try {
            json.decodeFromString<SharedDeviceRegistry>(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) { return }
        remoteEncrypted = remote.encrypted
        val localDeviceId = localDriveIndex.getLocalDeviceId()
        for (device in remote.devices) {
            if (device.id == localDeviceId) continue
            val existing = localDriveIndex.getDevices()[device.id]
            if (existing == null) {
                localDriveIndex.setDevice(
                    device.id,
                    DeviceInfo(name = device.name, firstSeen = device.lastSeen, lastSeen = device.lastSeen),
                )
            }
        }
        Log.d(TAG, "syncRegistryFromDrive: imported ${remote.devices.size} device(s)")
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

    companion object {
        private const val TAG = "DeviceRegistry"
        private const val REGISTRY_FILE = "devices.json"
    }
}

@Serializable
data class SharedDevice(
    val id: String,
    val name: String,
    val lastSeen: Long,
)

@Serializable
data class SharedDeviceRegistry(
    val devices: List<SharedDevice>,
    val encrypted: Boolean = false,
)
