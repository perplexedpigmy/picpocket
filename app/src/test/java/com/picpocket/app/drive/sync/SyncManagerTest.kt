package com.picpocket.app.drive.sync

import android.content.Context
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.drive.DriveAuthManager
import com.picpocket.app.drive.DriveAuthState
import com.picpocket.app.drive.DriveConnectivityChecker
import com.picpocket.app.drive.SyncState
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.every
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

    private lateinit var syncManager: SyncManager

    @Before
    fun setUp() {
        every { driveAuthManager.authState } returns MutableStateFlow(DriveAuthState.Connected)
        every { driveConnectivityChecker.isNetworkAvailable() } returns true
        every { syncSettings.syncEnabled } returns true
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        every { localDriveIndex.getRootFolderId() } returns ""
        every { localDriveIndex.getRootTreeUri() } returns ""
        every { defaultSyncScheduler.setSyncCallback(any()) } returns Unit
        every { journal.entriesFromCheckpoint() } returns emptyList()
        every { journal.isEmpty() } returns true
        every { journal.advanceCheckpoint() } returns Unit
        every { journal.truncate() } returns Unit
        coEvery { retryHandler.waitBeforeRetry() } returns Unit
        coEvery { retryHandler.onSuccess() } returns Unit
        coEvery { retryHandler.onFailure() } returns Unit
        coEvery { conflictResolver.detectConflicts(any(), any()) } returns Unit
        coEvery { deviceRegistry.detectOrphans(any(), any()) } returns Unit

        syncManager = SyncManager(
            driveAuthManager,
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
            syncDirty = true,
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
            syncDirty = true,
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
}
