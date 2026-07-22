package com.picpocket.app.ui.screens.sync

import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.drive.DriveAuthManager
import com.picpocket.app.drive.DriveAuthState
import com.picpocket.app.drive.EncryptionManager
import com.picpocket.app.drive.PassphraseStore
import com.picpocket.app.drive.SyncState
import com.picpocket.app.drive.sync.ConflictResolver
import com.picpocket.app.drive.sync.DeviceRegistry
import com.picpocket.app.drive.sync.LocalDriveIndex
import com.picpocket.app.drive.sync.RetryHandler
import com.picpocket.app.drive.sync.SyncManager
import com.picpocket.app.drive.sync.SyncSettings
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class SyncViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val driveAuthManager = mockk<DriveAuthManager>(relaxed = true)
    private val syncManager = mockk<SyncManager>()
    private val syncSettings = mockk<SyncSettings>(relaxed = true)
    private val localDriveIndex = mockk<LocalDriveIndex>()
    private val conflictResolver = mockk<ConflictResolver>()
    private val deviceRegistry = mockk<DeviceRegistry>()
    private val retryHandler = mockk<RetryHandler>()
    private val encryptionManager = mockk<EncryptionManager>(relaxed = true)
    private val passphraseStore = mockk<PassphraseStore>(relaxed = true)

    private lateinit var viewModel: SyncViewModel

    @Before
    fun setUp() {
        every { driveAuthManager.authState } returns MutableStateFlow(DriveAuthState.Disconnected)
        every { syncManager.syncState } returns MutableStateFlow(SyncState.Idle)
        every { syncSettings.syncEnabled } returns false
        every { localDriveIndex.hasValidFolder() } returns false
        every { localDriveIndex.getRootFolderName() } returns ""
        every { conflictResolver.getActiveConflicts() } returns emptyList()
        every { deviceRegistry.getMyDeleted() } returns emptyList()
        every { deviceRegistry.getOthersDeleted() } returns emptyList()
        every { driveAuthManager.signInIntent } returns android.content.Intent()
        every { passphraseStore.getPassphrase() } returns null

        viewModel = SyncViewModel(
            app,
            driveAuthManager,
            syncManager,
            syncSettings,
            localDriveIndex,
            conflictResolver,
            deviceRegistry,
            retryHandler,
            encryptionManager,
            passphraseStore,
        )
    }

    @Test
    fun `initial state is Disconnected when not authenticated`() {
        assertEquals(ConnectionState.Disconnected, viewModel.uiState.value.connectionState)
    }

    @Test
    fun `verifyConnection sets Disconnected when not authenticated`() {
        every { driveAuthManager.authState } returns MutableStateFlow(DriveAuthState.Disconnected)
        viewModel.verifyConnection()
        assertEquals(ConnectionState.Disconnected, viewModel.uiState.value.connectionState)
    }

    @Test
    fun `verifyConnection sets Disconnected when no folder`() {
        every { driveAuthManager.authState } returns MutableStateFlow(DriveAuthState.Connected)
        viewModel.verifyConnection()
        assertEquals(ConnectionState.Disconnected, viewModel.uiState.value.connectionState)
    }

    @Test
    fun `verifyConnection sets Connected when authenticated and folder exists`() {
        every { driveAuthManager.authState } returns MutableStateFlow(DriveAuthState.Connected)
        every { localDriveIndex.hasValidFolder() } returns true
        every { localDriveIndex.getRootFolderName() } returns "My Drive Folder"

        viewModel.verifyConnection()

        val state = viewModel.uiState.value
        assertEquals(ConnectionState.Connected, state.connectionState)
        assertEquals("My Drive Folder", state.folderName)
    }

    @Test
    fun `verifyConnection populates counts when connected`() {
        every { driveAuthManager.authState } returns MutableStateFlow(DriveAuthState.Connected)
        every { localDriveIndex.hasValidFolder() } returns true
        every { localDriveIndex.getRootFolderName() } returns "Folder"
        every { syncSettings.syncEnabled } returns true
        every { conflictResolver.getActiveConflicts() } returns listOf(mockk())
        every { deviceRegistry.getMyDeleted() } returns listOf(mockk())
        every { deviceRegistry.getOthersDeleted() } returns listOf(mockk(), mockk())

        viewModel.verifyConnection()

        val state = viewModel.uiState.value
        assertTrue(state.syncEnabled)
        assertEquals(1, state.conflictCount)
        assertEquals(1, state.trashCount)
        assertEquals(2, state.removedByOthersCount)
    }

    @Test
    fun `toggleSync updates sync enabled state`() {
        viewModel.toggleSync(true)
        assertTrue(viewModel.uiState.value.syncEnabled)

        viewModel.toggleSync(false)
        assertFalse(viewModel.uiState.value.syncEnabled)
    }

    @Test
    fun `syncNow calls retryHandler reset and performSync`() = runTest {
        every { retryHandler.reset() } returns Unit
        coEvery { syncManager.performSync() } returns Unit

        viewModel.syncNow()

        advanceUntilIdle()
        coVerify { syncManager.performSync() }
    }

    @Test
    fun `disconnect clears folder and signs out`() {
        every { localDriveIndex.clearFolder() } returns Unit

        viewModel.disconnect()

        assertEquals(ConnectionState.Disconnected, viewModel.uiState.value.connectionState)
        verify { localDriveIndex.clearFolder() }
    }

    @Test
    fun `handleSignInResult sets FolderPickRequired when no folder`() {
        every { driveAuthManager.authState } returns MutableStateFlow(DriveAuthState.Connected)
        every { localDriveIndex.hasValidFolder() } returns false

        viewModel.handleSignInResult(mockk(relaxed = true))

        assertEquals(SyncActionState.FolderPickRequired, viewModel.actionState.value)
    }

    @Test
    fun `handleFolderPickerResult accepts valid Drive URI`() {
        val uri = android.net.Uri.parse("content://com.google.android.apps.docs.storage/tree/abc")
        every { localDriveIndex.setRootTreeUri(any()) } returns Unit
        every { localDriveIndex.setRootFolderName(any()) } returns Unit
        every { localDriveIndex.hasValidFolder() } returns true
        every { localDriveIndex.getRootFolderName() } returns "My Folder"
        every { driveAuthManager.authState } returns MutableStateFlow(DriveAuthState.Connected)
        every { retryHandler.reset() } returns Unit

        viewModel.handleFolderPickerResult(uri)

        assertEquals(SyncActionState.Idle, viewModel.actionState.value)
    }

    @Test
    fun `handleFolderPickerResult rejects non-Drive URI`() {
        val uri = android.net.Uri.parse("content://other.provider/tree/abc")

        viewModel.handleFolderPickerResult(uri)

        assertTrue(viewModel.actionState.value is SyncActionState.Error)
    }

    @Test
    fun `handleFolderPickerResult handles null URI as cancelled`() {
        viewModel.handleFolderPickerResult(null)

        assertTrue(viewModel.actionState.value is SyncActionState.Error)
    }

    @Test
    fun `dismissAction resets action state to Idle`() {
        viewModel.dismissAction()
        assertEquals(SyncActionState.Idle, viewModel.actionState.value)
    }
}
