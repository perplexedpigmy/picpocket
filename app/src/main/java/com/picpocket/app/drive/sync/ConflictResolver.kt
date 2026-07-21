package com.picpocket.app.drive.sync

import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.StoredDocument
import javax.inject.Inject
import javax.inject.Singleton

data class ConflictInfo(
    val docId: String,
    val localVersion: StoredDocument?,
    val remoteVersion: StoredDocument?,
    val localTimestamp: Long,
    val remoteTimestamp: Long,
    val resolved: Boolean = false,
    val resolutionTimestamp: Long = 0L,
)

sealed interface ConflictResolution {
    data class KeepLocal(val docId: String) : ConflictResolution
    data class KeepRemote(val docId: String) : ConflictResolution
    data object NoConflict : ConflictResolution
}

@Singleton
class ConflictResolver @Inject constructor(
    private val documentStore: DocumentStore,
    private val downloadEngine: DownloadEngine,
    private val uploadEngine: UploadEngine,
    private val localDriveIndex: LocalDriveIndex,
) {
    private val conflicts = mutableListOf<ConflictInfo>()

    fun getActiveConflicts(): List<ConflictInfo> = conflicts.filter { !it.resolved }

    suspend fun detectConflicts(localDocs: List<StoredDocument>, remoteDocs: List<DownloadEngine.RemoteDocument>) {
        conflicts.clear()
        for (local in localDocs) {
            val remote = remoteDocs.find { it.docId == local.id }
            if (remote == null || remote.isDeleted || remote.metadata == null) continue

            val localVersion = local.syncVersion
            val remoteVersion = remote.metadata.syncVersion

            if (localVersion != remoteVersion) {
                val olderIsAncestor = localVersion == remoteVersion - 1 || remoteVersion == localVersion - 1
                if (!olderIsAncestor && localVersion > 0 && remoteVersion > 0) {
                    conflicts.add(
                        ConflictInfo(
                            docId = local.id,
                            localVersion = local,
                            remoteVersion = remote.metadata,
                            localTimestamp = local.syncTimestamp,
                            remoteTimestamp = remote.metadata.syncTimestamp,
                        ),
                    )
                }
            }
        }
    }

    suspend fun resolveConflict(docId: String, keepLocal: Boolean): ConflictResolution {
        val conflict = conflicts.find { it.docId == docId } ?: return ConflictResolution.NoConflict
        return if (keepLocal) {
            uploadEngine.uploadDocument(conflict.localVersion ?: return ConflictResolution.NoConflict)
            conflicts.remove(conflict)
            ConflictResolution.KeepLocal(docId)
        } else {
            val remote = conflict.remoteVersion
            if (remote != null) {
                documentStore.writeMetadata(docId, remote)
            }
            conflicts.remove(conflict)
            ConflictResolution.KeepRemote(docId)
        }
    }

    suspend fun dismissConflict(docId: String) {
        val idx = conflicts.indexOfFirst { it.docId == docId }
        if (idx >= 0) {
            conflicts.removeAt(idx)
        }
    }
}
