package com.picpocket.app.drive.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DriveIndex(
    val devices: MutableMap<String, DeviceInfo> = mutableMapOf(),
    val documents: MutableMap<String, DocumentDriveInfo> = mutableMapOf(),
    var localDeviceId: String = "",
    var rootFolderId: String = "",
    var rootTreeUri: String = "",
)

@Serializable
data class DeviceInfo(
    val name: String,
    val firstSeen: Long,
    val lastSeen: Long,
)

@Serializable
data class DocumentDriveInfo(
    var folderId: String = "",
    val pages: MutableMap<String, String> = mutableMapOf(),
    var syncVersion: Int = 0,
    var syncTimestamp: Long = 0L,
    var lastKnownEtag: String = "",
)

@Singleton
class LocalDriveIndex @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val file = File(context.filesDir, "drive_index.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private var index = load()

    fun getDocumentInfo(docId: String): DocumentDriveInfo =
        index.documents[docId] ?: DocumentDriveInfo()

    fun setDocumentInfo(docId: String, info: DocumentDriveInfo) {
        index.documents[docId] = info
        save()
    }

    fun removeDocument(docId: String) {
        index.documents.remove(docId)
        save()
    }

    fun getLocalDeviceId(): String = index.localDeviceId

    fun setLocalDeviceId(id: String) {
        index.localDeviceId = id
        save()
    }

    fun getDevices(): Map<String, DeviceInfo> = index.devices

    fun setDevice(id: String, info: DeviceInfo) {
        index.devices[id] = info
        save()
    }

    fun removeDevice(id: String) {
        index.devices.remove(id)
        save()
    }

    fun getAllKnownDeviceIds(): Set<String> = index.devices.keys

    fun getAllTrackedDocumentIds(): Set<String> = index.documents.keys

    fun getRootFolderId(): String = index.rootFolderId

    fun setRootFolderId(id: String) {
        index.rootFolderId = id
        save()
    }

    fun getRootTreeUri(): String = index.rootTreeUri

    fun setRootTreeUri(uri: String) {
        index.rootTreeUri = uri
        save()
    }

    fun hasValidFolder(): Boolean {
        return index.rootTreeUri.isNotBlank()
    }

    fun clearFolder() {
        index.rootFolderId = ""
        index.rootTreeUri = ""
        save()
    }

    private fun load(): DriveIndex {
        return if (file.exists()) {
            try {
                json.decodeFromString(file.readText())
            } catch (_: Exception) {
                DriveIndex()
            }
        } else {
            DriveIndex()
        }
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(index))
    }
}
