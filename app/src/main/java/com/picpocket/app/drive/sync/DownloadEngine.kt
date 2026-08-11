package com.picpocket.app.drive.sync

import com.picpocket.app.debug.Category
import com.picpocket.app.debug.Tracing
import androidx.documentfile.provider.DocumentFile
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.MetadataNaming
import com.picpocket.app.data.store.StoredDocument
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadEngine @Inject constructor(
    private val driveFileManager: DriveFileManager,
    private val documentStore: DocumentStore,
    private val localDriveIndex: LocalDriveIndex,
) {
    companion object {
        private const val TAG = "DownloadEngine"
    }
    private val json = Json { ignoreUnknownKeys = true }

    data class RemoteDocument(
        val docId: String,
        val fileNames: List<String>,
        val metadata: StoredDocument?,
        val version: Int,
        val passphrase: Int,
        val isDeleted: Boolean,
    )

    suspend fun listRemoteDocuments(
        remoteCache: Map<String, List<DocumentFile>>? = null,
    ): List<RemoteDocument> {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) { Tracing.e(Category.DRIVE_API, TAG, "listRemoteDocuments: treeUri blank"); return emptyList() }

        val docIds = driveFileManager.listDocFolders(treeUri)
        Tracing.d(Category.DRIVE_FILES, TAG, "listRemoteDocuments: docIds=${docIds.toList()}")
        return docIds.mapNotNull { docId ->
            val fileNames = driveFileManager.listFileNames(treeUri, docId, remoteCache)
            Tracing.d(Category.DRIVE_FILES, TAG, "listRemoteDocuments: docId=$docId fileNames=$fileNames")
            val hasDeleted = ".deleted" in fileNames

            val metaEntry = fileNames
                .mapNotNull { name -> MetadataNaming.parse(name)?.let { vp -> name to vp } }
                .maxByOrNull { it.second.first }

            val metadata = if (metaEntry != null && !hasDeleted) {
                val data = driveFileManager.readMetadataJson(treeUri, docId, metaEntry.first, remoteCache)
                Tracing.d(Category.DRIVE_FILES, TAG, "listRemoteDocuments: docId=$docId metadata=${data?.size} bytes")
                if (data != null) {
                    try {
                        json.decodeFromString<StoredDocument>(String(data, Charsets.UTF_8))
                    } catch (_: Exception) {
                        Tracing.w(Category.DRIVE_FILES, TAG, "listRemoteDocuments: docId=$docId metadata deserialize failed")
                        null
                    }
                } else null
            } else null

            RemoteDocument(
                docId = docId,
                fileNames = fileNames.filter { it != ".deleted" && !MetadataNaming.isMetadata(it) },
                metadata = metadata,
                version = metaEntry?.second?.first ?: 0,
                passphrase = metaEntry?.second?.second ?: 0,
                isDeleted = hasDeleted,
            )
        }
    }

    suspend fun downloadFile(
        treeUri: String, docId: String, fileName: String,
        remoteCache: Map<String, List<DocumentFile>>? = null,
    ): ByteArray? {
        return driveFileManager.readFile(treeUri, docId, fileName, remoteCache)
    }

    suspend fun downloadTombstone(
        treeUri: String, docId: String,
        remoteCache: Map<String, List<DocumentFile>>? = null,
    ): ByteArray? {
        return driveFileManager.readFile(treeUri, docId, ".deleted", remoteCache)
    }

    suspend fun pullDocument(
        remote: RemoteDocument,
        remoteCache: Map<String, List<DocumentFile>>? = null,
    ): Boolean {
        val meta = remote.metadata ?: return false
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return false

        for (filename in remote.fileNames) {
            val data = driveFileManager.readFile(treeUri, remote.docId, filename, remoteCache)
            if (data != null) {
                val pageFile = documentStore.pageFile(remote.docId, filename)
                pageFile.parentFile?.mkdirs()
                pageFile.writeBytes(data)
            }
        }

        val dir = documentStore.documentDir(remote.docId)
        val remoteSet = remote.fileNames.toSet()
        for (f in dir.listFiles() ?: emptyArray()) {
            val name = f.name
            if (name !in remoteSet && !MetadataNaming.isMetadata(name) && !name.endsWith(".tmp")) {
                f.delete()
            }
        }

        return documentStore.writeMetadataAt(remote.docId, meta, remote.version, remote.passphrase).isSuccess
    }
}
