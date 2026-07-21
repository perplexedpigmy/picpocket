package com.picpocket.app.drive.sync

import com.picpocket.app.drive.EncryptionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DriveFileManagerTest {

    private val driveAuthManager = mockk<com.picpocket.app.drive.DriveAuthManager>()
    private val encryptionManager = EncryptionManager()

    private lateinit var manager: DriveFileManager

    @Before
    fun setUp() {
        encryptionManager.setPassphrase("test")
        manager = DriveFileManager(driveAuthManager, encryptionManager)
    }

    @Test
    fun `createOrGetFolder returns null when not authenticated`() = runTest {
        coEvery { driveAuthManager.driveService } returns null
        val result = manager.createOrGetFolder("test-doc")
        assert(result == null)
    }

    @Test
    fun `listAllFolders returns empty when not authenticated`() = runTest {
        coEvery { driveAuthManager.driveService } returns null
        val result = manager.listAllFolders()
        assert(result.isEmpty())
    }

    @Test
    fun `findFilesInFolder returns empty when not authenticated`() = runTest {
        coEvery { driveAuthManager.driveService } returns null
        val result = manager.findFilesInFolder("folder-id")
        assert(result.isEmpty())
    }

    @Test
    fun `uploadFile returns null when not authenticated`() = runTest {
        coEvery { driveAuthManager.driveService } returns null
        val result = manager.uploadFile("folder-id", "test.jpg", byteArrayOf(1, 2, 3))
        assert(result == null)
    }

    @Test
    fun `downloadFile returns null when not authenticated`() = runTest {
        coEvery { driveAuthManager.driveService } returns null
        val result = manager.downloadFile("file-id")
        assert(result == null)
    }

    @Test
    fun `deleteFile does not throw when not authenticated`() = runTest {
        coEvery { driveAuthManager.driveService } returns null
        manager.deleteFile("file-id")
    }

    @Test
    fun `findFolder returns null when not authenticated`() = runTest {
        coEvery { driveAuthManager.driveService } returns null
        val result = manager.findFolder("test-doc")
        assert(result == null)
    }

    @Test
    fun `createFolder returns null when not authenticated`() = runTest {
        coEvery { driveAuthManager.driveService } returns null
        val result = manager.createFolder("test-doc")
        assert(result == null)
    }
}
