package com.picpocket.app.drive.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.drive.EncryptionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DriveFileManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val encryptionManager = EncryptionManager()

    private lateinit var manager: DriveFileManager

    @Before
    fun setUp() {
        encryptionManager.setPassphrase("test-passphrase-32-chars-long!!")
        manager = DriveFileManager(context, encryptionManager)
    }

    @Test
    fun `listDocFolders returns empty for invalid treeUri`() = runTest {
        val result = manager.listDocFolders("content://invalid-tree-uri/")
        assert(result.isEmpty())
    }

    @Test
    fun `listFileNames returns empty for invalid treeUri`() = runTest {
        val result = manager.listFileNames("content://invalid-tree-uri/", "doc-123")
        assert(result.isEmpty())
    }

    @Test
    fun `readFile returns null for invalid treeUri`() = runTest {
        val result = manager.readFile("content://invalid-tree-uri/", "doc-123", "page.jpg")
        assert(result == null)
    }

    @Test
    fun `writeFile returns false for invalid treeUri`() = runTest {
        val result = manager.writeFile("content://invalid-tree-uri/", "doc-123", "page.jpg", byteArrayOf(1, 2, 3))
        assert(result == false)
    }

    @Test
    fun `deleteFileByName returns false for invalid treeUri`() = runTest {
        val result = manager.deleteFileByName("content://invalid-tree-uri/", "doc-123", "page.jpg")
        assert(result == false)
    }

    @Test
    fun `createDocFolder returns false for invalid treeUri`() = runTest {
        val result = manager.createDocFolder("content://invalid-tree-uri/", "doc-123")
        assert(result == false)
    }

    @Test
    fun `readMetadataJson returns null for invalid treeUri`() = runTest {
        val result = manager.readMetadataJson("content://invalid-tree-uri/", "doc-123")
        assert(result == null)
    }
}
