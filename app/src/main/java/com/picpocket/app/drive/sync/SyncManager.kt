package com.picpocket.app.drive.sync

import android.content.Context
import android.os.Build
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.drive.DriveAuthManager
import com.picpocket.app.drive.DriveAuthState
import com.picpocket.app.drive.DriveConnectivityChecker
import com.picpocket.app.drive.SyncState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor(
    private val driveAuthManager: DriveAuthManager,
    private val documentStore: DocumentStore,
    private val uploadEngine: UploadEngine,
    private val downloadEngine: DownloadEngine,
    private val localDriveIndex: LocalDriveIndex,
    private val driveConnectivityChecker: DriveConnectivityChecker,
    private val defaultSyncScheduler: DefaultSyncScheduler,
    private val conflictResolver: ConflictResolver,
    private val deviceRegistry: DeviceRegistry,
    private val retryHandler: RetryHandler,
    private val syncSettings: SyncSettings,
    @ApplicationContext private val context: Context,
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var isSyncing = false

    init {
        defaultSyncScheduler.setSyncCallback { performSync() }
        ensureDeviceRegistered()
    }

    private fun ensureDeviceRegistered() {
        if (localDriveIndex.getLocalDeviceId().isBlank()) {
            val deviceId = java.util.UUID.randomUUID().toString()
            localDriveIndex.setLocalDeviceId(deviceId)
            localDriveIndex.setDevice(
                deviceId,
                DeviceInfo(
                    name = Build.MODEL,
                    firstSeen = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun performSync() {
        if (!syncSettings.syncEnabled) return
        val authState = driveAuthManager.authState.value
        if (authState !is DriveAuthState.Connected) return
        if (!driveConnectivityChecker.isNetworkAvailable()) return
        if (isSyncing) return

        isSyncing = true
        _syncState.value = SyncState.Syncing
        try {
            retryHandler.waitBeforeRetry()

            val localDocs = documentStore.listDocuments().getOrDefault(emptyList())
            val remoteDocs = downloadEngine.listRemoteDocuments()

            for (doc in localDocs) {
                if (doc.syncExclude) continue
                val remote = remoteDocs.find { it.docId == doc.id }

                if (remote?.isDeleted == true) {
                    continue
                }

                if (doc.syncDirty || remote == null) {
                    uploadEngine.uploadDocument(doc)
                }
            }

            for (remote in remoteDocs) {
                if (remote.isDeleted) continue
                val localExists = localDocs.any { it.id == remote.docId }
                if (!localExists && remote.metadata != null) {
                    downloadFullDocument(remote)
                }
            }

            conflictResolver.detectConflicts(localDocs, remoteDocs)
            deviceRegistry.detectOrphans(localDocs, remoteDocs)

            retryHandler.onSuccess()
            _syncState.value = SyncState.Idle
        } catch (e: Exception) {
            retryHandler.onFailure()
            _syncState.value = SyncState.Error(e.message ?: "Sync failed")
        } finally {
            isSyncing = false
        }
    }

    private suspend fun downloadFullDocument(remote: DownloadEngine.RemoteDocument) {
        val meta = remote.metadata ?: return
        documentStore.writeMetadata(meta.id, meta)

        for ((filename, fileId) in remote.files) {
            val data = downloadEngine.downloadFile(fileId)
            if (data != null) {
                val pageFile = documentStore.pageFile(meta.id, filename) ?: continue
                pageFile.parentFile?.mkdirs()
                pageFile.writeBytes(data)
            }
        }
    }
}
