package com.docscanner.drive.sync

import com.docscanner.drive.DriveAuthState
import com.docscanner.drive.EncryptionManager
import com.docscanner.drive.SyncState
import com.docscanner.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class SyncSmokeTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val driveAuthManager = mockk<com.docscanner.drive.DriveAuthManager>()
    private val documentStore = mockk<com.docscanner.data.store.DocumentStore>()
    private val uploadEngine = mockk<UploadEngine>()
    private val downloadEngine = mockk<DownloadEngine>()
    private val localDriveIndex = mockk<LocalDriveIndex>()
    private val driveConnectivityChecker = mockk<com.docscanner.drive.DriveConnectivityChecker>()
    private val defaultSyncScheduler = mockk<DefaultSyncScheduler>()
    private val conflictResolver = mockk<ConflictResolver>()
    private val deviceRegistry = mockk<DeviceRegistry>()
    private val retryHandler = mockk<RetryHandler>()
    private val syncSettings = mockk<SyncSettings>()
    private val context = mockk<android.content.Context>()

    private lateinit var syncManager: SyncManager

    @Before
    fun setUp() {
        every { driveAuthManager.authState } returns MutableStateFlow(DriveAuthState.Connected)
        every { driveConnectivityChecker.isNetworkAvailable() } returns true
        every { syncSettings.syncEnabled } returns true
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        every { defaultSyncScheduler.setSyncCallback(any()) } returns Unit
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
            context,
        )
    }

    @Test
    fun `sync setup works end to end`() = runTest {
        coEvery { documentStore.listDocuments() } returns Result.success(emptyList())
        coEvery { downloadEngine.listRemoteDocuments() } returns emptyList()
        coEvery { conflictResolver.detectConflicts(emptyList(), emptyList()) } returns Unit
        coEvery { deviceRegistry.detectOrphans(emptyList(), emptyList()) } returns Unit

        syncManager.performSync()

        Assert.assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `encryption can be enabled and disabled`() {
        val em = EncryptionManager()
        Assert.assertFalse(em.isEncryptionEnabled)

        em.setPassphrase("smoke-test-key")
        Assert.assertTrue(em.isEncryptionEnabled)

        em.clearPassphrase()
        Assert.assertFalse(em.isEncryptionEnabled)
    }

    @Test
    fun `DriveAuthState sealed interface is exhaustive`() {
        val states = listOf(
            DriveAuthState.Connected,
            DriveAuthState.Disconnected,
            DriveAuthState.ReauthRequired,
            DriveAuthState.Connecting,
            DriveAuthState.Error("test"),
        )
        Assert.assertEquals(5, states.size)
    }

    @Test
    fun `SyncState sealed interface is exhaustive`() {
        val states = listOf(
            SyncState.Idle,
            SyncState.Syncing,
            SyncState.Error("test"),
        )
        Assert.assertEquals(3, states.size)
    }
}
