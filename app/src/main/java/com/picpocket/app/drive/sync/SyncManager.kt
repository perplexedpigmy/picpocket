package com.picpocket.app.drive.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import com.picpocket.app.data.repository.DocumentRepository
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
    private val documentRepository: DocumentRepository,
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
    private val syncMutex: SyncMutex,
    @ApplicationContext private val context: Context,
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var isSyncing = false

    init {
        defaultSyncScheduler.setSyncCallback { performSync() }
        ensureDeviceRegistered()
    }

    private fun getDeviceName(): String {
        val systemName = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) { null }
        return if (!systemName.isNullOrBlank()) systemName else Build.MODEL
    }

    private fun ensureDeviceRegistered() {
        if (localDriveIndex.getLocalDeviceId().isBlank()) {
            val deviceId = java.util.UUID.randomUUID().toString()
            localDriveIndex.setLocalDeviceId(deviceId)
            localDriveIndex.setDevice(
                deviceId,
                DeviceInfo(
                    name = getDeviceName(),
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
        if (!localDriveIndex.hasValidFolder()) { Log.d(TAG, "performSync: no folder configured"); _syncState.value = SyncState.Error("No folder configured"); return }

        Log.d(TAG, "performSync: starting rootFolderId='${localDriveIndex.getRootFolderId()}'")
        isSyncing = true
        _syncState.value = SyncState.Syncing
        try {
            syncMutex.initialize()
            if (!syncMutex.acquire()) {
                Log.d(TAG, "performSync: mutex locked by another device")
                _syncState.value = SyncState.Idle
                return
            }
            withContext(Dispatchers.IO) {
                withTimeout(30_000L) {
                    retryHandler.waitBeforeRetry()
                    Log.d(TAG, "performSync: waitBeforeRetry done")

                    val localDocs = documentStore.listDocuments().getOrDefault(emptyList())
                    Log.d(TAG, "performSync: localDocs count=${localDocs.size}")

                    refreshSafCache()

                    Log.d(TAG, "performSync: listing remote docs...")
                    val remoteDocs = downloadEngine.listRemoteDocuments()

                    deviceRegistry.syncRegistryFromDrive()
                    deviceRegistry.syncRegistryToDrive()

                    val excludeIds = localDocs.filter { it.syncExclude || remoteDocs.any { r -> r.docId == it.id && r.isDeleted } }.map { it.id }.toSet()

                    conflictResolver.detectConflicts(localDocs, remoteDocs)
                    val conflictIds = conflictResolver.getActiveConflicts().map { it.docId }.toSet()

                    processJournalEntries()

                    for (doc in localDocs) {
                        if (doc.id in excludeIds) continue
                        if (doc.id in conflictIds) continue
                        val remote = remoteDocs.find { it.docId == doc.id }
                        val matched = when {
                            remote == null && doc.syncVersion == 0 -> "condition1:remoteNull+syncVer0"
                            remote == null && journal.isEmpty() -> "condition2:remoteNull+journalEmpty"
                            remote != null && doc.syncVersion == 0 && journal.isEmpty() -> "condition3:remoteExists+syncVer0+journalEmpty"
                            else -> null
                        }
                        if (matched != null) {
                            Log.d(TAG, "performSync: doc=${doc.id} uploading via $matched")
                            try {
                                val ok = uploadEngine.uploadDocument(doc)
                                if (!ok) Log.w(TAG, "performSync: uploadDocument returned false for doc=${doc.id}")
                            } catch (e: Exception) {
                                Log.e(TAG, "performSync: uploadDocument threw for doc=${doc.id}: ${e.message}")
                            }
                        } else {
                            Log.d(TAG, "performSync: doc=${doc.id} skipped (remote=${remote != null} syncVer=${doc.syncVersion} journalEmpty=${journal.isEmpty()})")
                        }
                    }

                    for (remote in remoteDocs) {
                        if (remote.isDeleted) continue
                        if (remote.docId in conflictIds) continue
                        val localExists = localDocs.any { it.id == remote.docId }
                        if (!localExists && remote.metadata != null) {
                            downloadFullDocument(remote)
                        }
                    }

                    for (remote in remoteDocs) {
                        if (remote.isDeleted) continue
                        if (remote.metadata == null) continue
                        if (remote.docId in conflictIds) continue
                        val local = localDocs.find { it.id == remote.docId }
                        if (local == null) continue
                        if (remote.metadata.syncVersion > local.syncVersion) {
                            Log.d(TAG, "performSync: doc=${remote.docId} remote v${remote.metadata.syncVersion} > local v${local.syncVersion} downloading update")
                            downloadFullDocument(remote)
                        }
                    }

                    for (local in localDocs) {
                        if (local.id in excludeIds) continue
                        if (local.id in conflictIds) continue
                        val treeUri = localDriveIndex.getRootTreeUri()
                        if (treeUri.isNotBlank()) {
                            try {
                                val missing = downloadEngine.checkFiles(treeUri, local.id, local)
                                if (missing.isNotEmpty()) {
                                    Log.d(TAG, "performSync: doc=${local.id} missing=$missing re-uploading")
                                    uploadEngine.uploadDocument(local)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "performSync: checkFiles threw for doc=${local.id}: ${e.message}")
                            }
                        }
                    }

                    documentRepository.notifyDocumentsChanged()

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
            syncMutex.release()
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
            try {
                journal.advanceCheckpoint()
            } catch (_: Exception) {
                Log.w(TAG, "processJournalEntries: advanceCheckpoint failed")
            }
        }
        try {
            journal.truncate()
        } catch (_: Exception) {
            Log.w(TAG, "processJournalEntries: truncate failed")
        }
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

    private suspend fun refreshSafCache() {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return
        try {
            val resolver = context.contentResolver ?: return
            val uri = Uri.parse(treeUri)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            resolver.refresh(docUri, null, null)
        } catch (e: Throwable) {
            Log.w(TAG, "refreshSafCache: failed ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SyncManager"
    }
}
