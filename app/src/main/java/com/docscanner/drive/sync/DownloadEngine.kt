package com.docscanner.drive.sync

import com.docscanner.data.store.DocumentStore
import com.docscanner.data.store.StoredDocument
import com.google.api.services.drive.model.File
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadEngine @Inject constructor(
    private val driveFileManager: DriveFileManager,
    private val documentStore: DocumentStore,
    private val localDriveIndex: LocalDriveIndex,
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class RemoteDocument(
        val docId: String,
        val folderId: String,
        val files: Map<String, String>,
        val metadata: StoredDocument?,
        val isDeleted: Boolean,
    )

    suspend fun listRemoteDocuments(): List<RemoteDocument> {
        val folders = driveFileManager.listAllFolders()
        return folders.mapNotNull { folder: File ->
            val docId = folder.name
            val files = driveFileManager.findFilesInFolder(folder.id)
            val fileMap: Map<String, String> = files.associate { file: File -> file.name to file.id }

            val hasDeleted = fileMap.containsKey(".deleted")
            val metadataId = fileMap["metadata.json"]
            val metadata = if (metadataId != null && !hasDeleted) {
                val data = driveFileManager.downloadFile(metadataId)
                if (data != null) {
                    try {
                        json.decodeFromString<StoredDocument>(String(data, Charsets.UTF_8))
                    } catch (_: Exception) {
                        null
                    }
                } else null
            } else null

            RemoteDocument(
                docId = docId,
                folderId = folder.id,
                files = fileMap.filter { (key, _) -> key != ".deleted" && key != "metadata.json" },
                metadata = metadata,
                isDeleted = hasDeleted,
            )
        }
    }

    suspend fun downloadFile(fileId: String): ByteArray? {
        return driveFileManager.downloadFile(fileId)
    }
}
