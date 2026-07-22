package com.picpocket.app.drive.sync

import android.content.Context
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.data.store.StoredPage
import com.picpocket.app.drive.DriveAuthManager
import com.picpocket.app.drive.DriveAuthState
import com.picpocket.app.drive.DriveConnectivityChecker
import com.picpocket.app.drive.SyncState
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class SyncManagerTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val driveAuthManager = mockk<DriveAuthManager>()
    private val documentRepository = mockk<DocumentRepository>()
    private val documentStore = mockk<DocumentStore>()
    private val uploadEngine = mockk<UploadEngine>()
    private val downloadEngine = mockk<DownloadEngine>()
    private val localDriveIndex = mockk<LocalDriveIndex>()
    private val driveConnectivityChecker = mockk<DriveConnectivityChecker>()
    private val defaultSyncScheduler = mockk<DefaultSyncScheduler>()
    private val conflictResolver = mockk<ConflictResolver>()
    private val deviceRegistry = mockk<DeviceRegistry>()
    private val retryHandler = mockk<RetryHandler>()
    private val syncSettings = mockk<SyncSettings>()
    private val context = mockk<Context>()
    private val journal = mockk<SyncJournal>(relaxed = true)
    private val syncMutex = mockk<SyncMutex>()

    private lateinit var syncManager: SyncManager

    @Before
    fun setUp() {
        every { driveAuthManager.authState } returns MutableStateFlow(DriveAuthState.Connected)
        every { driveConnectivityChecker.isNetworkAvailable() } returns true
        every { syncSettings.syncEnabled } returns true
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        every { localDriveIndex.getRootFolderId() } returns ""
        every { localDriveIndex.getRootTreeUri() } returns ""
        every { localDriveIndex.hasValidFolder() } returns true
        every { defaultSyncScheduler.setSyncCallback(any()) } returns Unit
        every { journal.entriesFromCheckpoint() } returns emptyList()
        every { journal.isEmpty() } returns true
        every { journal.advanceCheckpoint() } returns Unit
        every { journal.truncate() } returns Unit
        coEvery { retryHandler.waitBeforeRetry() } returns Unit
        coEvery { retryHandler.onSuccess() } returns Unit
        coEvery { retryHandler.onFailure() } returns Unit
        coEvery { conflictResolver.detectConflicts(any(), any()) } returns Unit
        every { conflictResolver.getActiveConflicts() } returns emptyList()
        coEvery { deviceRegistry.detectOrphans(any(), any()) } returns Unit
        every { documentRepository.notifyDocumentsChanged() } returns Unit
        coEvery { deviceRegistry.syncRegistryFromDrive() } returns Unit
        coEvery { deviceRegistry.syncRegistryToDrive() } returns Unit
        coEvery { syncMutex.initialize() } returns Unit
        coEvery { syncMutex.acquire() } returns true
        coEvery { syncMutex.release() } returns Unit

        syncManager = SyncManager(
            driveAuthManager,
            documentRepository,
            documentStore,
            uploadEngine,
            downloadEngine,
            localDriveIndex,
            driveConnectivityChecker,
            defaultSyncScheduler,
            conflictResolver,
            deviceRegistry,
            retryHandler,
            syncSettings,
            journal,
            syncMutex,
            context,
        )
    }

    @Test
    fun `sync returns idle when not connected`() = runTest {
        every { driveAuthManager.authState } returns MutableStateFlow(DriveAuthState.Disconnected)
        val state = syncManager.syncState.value
        assertEquals(SyncState.Idle, state)
    }

    @Test
    fun `sync returns idle when network unavailable`() = runTest {
        every { driveConnectivityChecker.isNetworkAvailable() } returns false
        val state = syncManager.syncState.value
        assertEquals(SyncState.Idle, state)
    }

    @Test
    fun `sync returns idle when sync disabled`() = runTest {
        every { syncSettings.syncEnabled } returns false
        val state = syncManager.syncState.value
        assertEquals(SyncState.Idle, state)
    }

    @Test
    fun `sync successfully uploads dirty docs`() = runTest {
        val doc = StoredDocument(
            id = "doc-1",
            name = "Test",
            createdAt = 0L,
            updatedAt = 0L,
        )
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(doc))
        coEvery { downloadEngine.listRemoteDocuments() } returns emptyList()
        coEvery { uploadEngine.uploadDocument(doc) } returns true
        coEvery { conflictResolver.detectConflicts(any(), any()) } returns Unit
        coEvery { deviceRegistry.detectOrphans(any(), any()) } returns Unit

        syncManager.performSync()

        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync does not upload excluded docs`() = runTest {
        val doc = StoredDocument(
            id = "doc-1",
            name = "Test",
            createdAt = 0L,
            updatedAt = 0L,
            syncExclude = true,
        )
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(doc))
        coEvery { downloadEngine.listRemoteDocuments() } returns emptyList()

        syncManager.performSync()

        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync downloads remote docs not present locally`() = runTest {
        coEvery { documentStore.listDocuments() } returns Result.success(emptyList())
        val remote = DownloadEngine.RemoteDocument(
            docId = "remote-1",
            fileNames = listOf("page_001.jpg"),
            metadata = StoredDocument(id = "remote-1", name = "Remote", createdAt = 0L, updatedAt = 0L),
            isDeleted = false,
        )
        coEvery { downloadEngine.listRemoteDocuments() } returns listOf(remote)
        coEvery { documentStore.writeMetadata(any(), any()) } returns Result.success(Unit)
        coEvery { documentStore.pageFile(any(), any()) } returns java.io.File.createTempFile("test", ".jpg")
        coEvery { downloadEngine.downloadFile(any(), any(), any()) } returns byteArrayOf(1, 2, 3)

        syncManager.performSync()

        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync handles exception gracefully`() = runTest {
        coEvery { documentStore.listDocuments() } throws RuntimeException("Network error")
        coEvery { retryHandler.onFailure() } returns Unit

        syncManager.performSync()

        val state = syncManager.syncState.value
        assert(state is SyncState.Error)
    }

    @Test
    fun `sync short-circuits when mutex locked by another device`() = runTest {
        val doc = StoredDocument(id = "doc-1", name = "Test", createdAt = 0L, updatedAt = 0L)
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(doc))
        coEvery { downloadEngine.listRemoteDocuments() } returns emptyList()
        coEvery { syncMutex.acquire() } returns false

        syncManager.performSync()

        assertEquals(SyncState.Idle, syncManager.syncState.value)
        coEvery { syncMutex.release() } returns Unit
    }

    @Test
    fun `sync re-uploads when syncVersion is 0 and remote exists`() = runTest {
        val doc = StoredDocument(id = "doc-1", name = "Test", createdAt = 0L, updatedAt = 0L, syncVersion = 0)
        val remote = DownloadEngine.RemoteDocument(
            docId = "doc-1",
            fileNames = listOf("page_001.jpg", "metadata.json"),
            metadata = doc,
            isDeleted = false,
        )
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { journal.isEmpty() } returns true
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(doc))
        coEvery { downloadEngine.listRemoteDocuments() } returns listOf(remote)
        coEvery { uploadEngine.uploadDocument(doc) } returns true
        coEvery { driveConnectivityChecker.isNetworkAvailable() } returns true
        coEvery { downloadEngine.checkFiles("content://tree/", "doc-1", doc) } returns emptyList()

        syncManager.performSync()

        coVerify { uploadEngine.uploadDocument(doc) }
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync re-uploads when checkFiles reports missing files`() = runTest {
        val doc = StoredDocument(
            id = "doc-1", name = "Test", createdAt = 0L, updatedAt = 0L, syncVersion = 1,
            pages = mutableListOf(StoredPage(pageNumber = 1, filename = "page_001.jpg", createdAt = 0L)),
        )
        val remote = DownloadEngine.RemoteDocument(
            docId = "doc-1",
            fileNames = listOf("metadata.json"),
            metadata = doc,
            isDeleted = false,
        )
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { journal.isEmpty() } returns true
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(doc))
        coEvery { downloadEngine.listRemoteDocuments() } returns listOf(remote)
        coEvery { downloadEngine.checkFiles("content://tree/", "doc-1", doc) } returns listOf("page_001.jpg")
        coEvery { uploadEngine.uploadDocument(doc) } returns true

        syncManager.performSync()

        coVerify { uploadEngine.uploadDocument(doc) }
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync excludes docs that are deleted remotely`() = runTest {
        val doc = StoredDocument(id = "doc-1", name = "Test", createdAt = 0L, updatedAt = 0L)
        val remote = DownloadEngine.RemoteDocument(
            docId = "doc-1",
            fileNames = listOf(".deleted"),
            metadata = null,
            isDeleted = true,
        )
        every { localDriveIndex.getRootTreeUri() } returns ""
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(doc))
        coEvery { downloadEngine.listRemoteDocuments() } returns listOf(remote)
        coEvery { uploadEngine.uploadDocument(doc) } returns true

        syncManager.performSync()

        coVerify(inverse = true) { uploadEngine.uploadDocument(doc) }
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync reports error when no folder configured`() = runTest {
        every { localDriveIndex.hasValidFolder() } returns false
        syncManager.performSync()
        val state = syncManager.syncState.value
        assertEquals(SyncState.Error("No folder configured"), state)
    }
}
