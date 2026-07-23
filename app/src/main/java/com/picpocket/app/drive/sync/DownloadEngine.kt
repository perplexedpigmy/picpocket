package com.picpocket.app.drive.sync

import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.picpocket.app.data.store.DocumentStore
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
        val isDeleted: Boolean,
    )

    suspend fun listRemoteDocuments(
        remoteCache: Map<String, List<DocumentFile>>? = null,
    ): List<RemoteDocument> {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) { Log.e(TAG, "listRemoteDocuments: treeUri blank"); return emptyList() }

        val docIds = driveFileManager.listDocFolders(treeUri)
        Log.d(TAG, "listRemoteDocuments: docIds=${docIds.toList()}")
        return docIds.mapNotNull { docId ->
            val fileNames = driveFileManager.listFileNames(treeUri, docId, remoteCache)
            Log.d(TAG, "listRemoteDocuments: docId=$docId fileNames=$fileNames")
            val hasDeleted = ".deleted" in fileNames
            val metadata = if ("metadata.json" in fileNames && !hasDeleted) {
                val data = driveFileManager.readMetadataJson(treeUri, docId, remoteCache)
                Log.d(TAG, "listRemoteDocuments: docId=$docId metadata=${data?.size} bytes")
                if (data != null) {
                    try {
                        json.decodeFromString<StoredDocument>(String(data, Charsets.UTF_8))
                    } catch (_: Exception) {
                        Log.w(TAG, "listRemoteDocuments: docId=$docId metadata deserialize failed")
                        null
                    }
                } else null
            } else null

            RemoteDocument(
                docId = docId,
                fileNames = fileNames.filter { it != ".deleted" && it != "metadata.json" },
                metadata = metadata,
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

    suspend fun checkFiles(
        treeUri: String, docId: String, doc: StoredDocument,
        remoteCache: Map<String, List<DocumentFile>>? = null,
    ): List<String> {
        val remoteNames = driveFileManager.listFileNames(treeUri, docId, remoteCache).toSet()
        return doc.pages
            .map { it.filename }
            .filter { it !in remoteNames } +
            if ("metadata.json" !in remoteNames) listOf("metadata.json") else emptyList()
    }
}
