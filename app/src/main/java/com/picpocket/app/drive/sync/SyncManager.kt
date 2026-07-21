package com.picpocket.app.drive.sync

import android.content.Context
import android.os.Build
import android.util.Log
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.drive.DriveAuthManager
import com.picpocket.app.drive.DriveAuthState
import com.picpocket.app.drive.DriveConnectivityChecker
import com.picpocket.app.drive.SyncState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
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
    private val journal: SyncJournal,
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
        if (!syncSettings.syncEnabled) { Log.d(TAG, "performSync: sync disabled"); return }
        val authState = driveAuthManager.authState.value
        if (authState !is DriveAuthState.Connected) { Log.d(TAG, "performSync: not connected (${authState::class.simpleName})"); return }
        if (!driveConnectivityChecker.isNetworkAvailable()) { Log.d(TAG, "performSync: no network"); return }
        if (isSyncing) { Log.d(TAG, "performSync: already syncing"); return }

        Log.d(TAG, "performSync: starting rootFolderId='${localDriveIndex.getRootFolderId()}'")
        isSyncing = true
        _syncState.value = SyncState.Syncing
        try {
            withContext(Dispatchers.IO) {
                withTimeout(30_000L) {
                    retryHandler.waitBeforeRetry()
                    Log.d(TAG, "performSync: waitBeforeRetry done")

                    val localDocs = documentStore.listDocuments().getOrDefault(emptyList())
                    Log.d(TAG, "performSync: localDocs count=${localDocs.size}")

                    Log.d(TAG, "performSync: listing remote docs...")
                    val remoteDocs = downloadEngine.listRemoteDocuments()

                    val excludeIds = localDocs.filter { it.syncExclude || remoteDocs.any { r -> r.docId == it.id && r.isDeleted } }.map { it.id }.toSet()

                    processJournalEntries()

                    for (doc in localDocs) {
                        if (doc.id in excludeIds) continue
                        val remote = remoteDocs.find { it.docId == doc.id }
                        if (remote == null && journal.isEmpty()) {
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
                }
            }
            _syncState.value = SyncState.Idle
            Log.d(TAG, "performSync: complete")
        } catch (e: TimeoutCancellationException) {
            retryHandler.onFailure()
            _syncState.value = SyncState.Error("Sync timed out")
            Log.d(TAG, "performSync: timed out")
        } catch (e: Exception) {
            retryHandler.onFailure()
            _syncState.value = SyncState.Error(e.message ?: "Sync failed")
            Log.d(TAG, "performSync: error ${e.message}")
        } finally {
            isSyncing = false
        }
    }

    fun synthesizeReEncryptPass() {
        val index = localDriveIndex
        val allDocIds = index.getAllTrackedDocumentIds()
        for (docId in allDocIds) {
            journal.append(JournalEntry.ReEncrypt(docId))
        }
    }

    private suspend fun processJournalEntries() {
        for (entry in journal.entriesFromCheckpoint()) {
            try {
                when (entry) {
                    is JournalEntry.AddPage -> uploadEngine.uploadPage(entry.docId, entry.pageNumber)
                    is JournalEntry.RemovePage -> uploadEngine.deletePage(entry.docId, entry.pageNumber)
                    is JournalEntry.ReplacePageImage -> uploadEngine.replacePageImage(entry.docId, entry.pageNumber)
                    is JournalEntry.ReorderPages -> uploadEngine.updateMetadata(entry.docId)
                    is JournalEntry.UpdateDocumentName -> uploadEngine.updateMetadata(entry.docId)
                    is JournalEntry.UpdatePageOcr -> uploadEngine.updateMetadata(entry.docId)
                    is JournalEntry.ReplacePages -> uploadEngine.replacePages(entry.docId, entry.keptFilenames).getOrNull()
                    is JournalEntry.ReEncrypt -> uploadEngine.reEncryptDocument(entry.docId)
                }
            } catch (_: Exception) {
                // continue processing remaining entries
            }
            journal.advanceCheckpoint()
        }
        journal.truncate()
    }

    private suspend fun downloadFullDocument(remote: DownloadEngine.RemoteDocument) {
        val meta = remote.metadata ?: return
        documentStore.writeMetadata(meta.id, meta)

        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return

        for (filename in remote.fileNames) {
            val data = downloadEngine.downloadFile(treeUri, meta.id, filename)
            if (data != null) {
                val pageFile = documentStore.pageFile(meta.id, filename)
                pageFile.parentFile?.mkdirs()
                pageFile.writeBytes(data)
            }
        }
    }

    companion object {
        private const val TAG = "SyncManager"
    }
}
