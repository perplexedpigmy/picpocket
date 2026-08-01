package com.picpocket.app.drive.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.drive.EncryptionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException

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

    @After
    fun tearDown() {
        unmockkStatic(DocumentFile::class)
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
    fun `writeFile returns Failed for invalid treeUri`() = runTest {
        val result = manager.writeFile("content://invalid-tree-uri/", "doc-123", "page.jpg", byteArrayOf(1, 2, 3))
        assert(result is WriteOutcome.Failed)
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

    @Test
    fun `writeFile returns Verified when createFile fails but existing target already has expected size`() = runTest {
        val fileName = "page_001.jpg"
        val data = byteArrayOf(1, 2, 3, 4)
        val expectedSize = encryptionManager.encrypt(data).size.toLong()

        val existingFile = mockk<DocumentFile>()
        every { existingFile.length() } returns expectedSize

        val docFolder = mockk<DocumentFile>()
        every { docFolder.listFiles() } returns arrayOf()
        every { docFolder.createFile(any(), fileName) } returns null
        every { docFolder.findFile(fileName) } returns existingFile
        every { docFolder.uri } returns Uri.parse("content://test/doc")

        val root = mockk<DocumentFile>()
        every { root.findFile("doc-123") } returns docFolder

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(context, Uri.parse("content://test/tree")) } returns root

        val result = manager.writeFile("content://test/tree", "doc-123", fileName, data)

        assert(result is WriteOutcome.Verified)
    }

    @Test
    fun `writeFile returns Verified when delete throws and createFile fails but target matches`() = runTest {
        val fileName = "page_001.jpg"
        val data = byteArrayOf(5, 6, 7)
        val expectedSize = encryptionManager.encrypt(data).size.toLong()

        val existingChild = mockk<DocumentFile>()
        every { existingChild.name } returns fileName
        every { existingChild.delete() } throws FileNotFoundException("file in use")

        val existingFile = mockk<DocumentFile>()
        every { existingFile.length() } returns expectedSize

        val docFolder = mockk<DocumentFile>()
        every { docFolder.listFiles() } returns arrayOf(existingChild)
        every { docFolder.createFile(any(), fileName) } returns null
        every { docFolder.findFile(fileName) } returns existingFile
        every { docFolder.uri } returns Uri.parse("content://test/doc")

        val root = mockk<DocumentFile>()
        every { root.findFile("doc-123") } returns docFolder

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(context, Uri.parse("content://test/tree")) } returns root

        val result = manager.writeFile("content://test/tree", "doc-123", fileName, data)

        assert(result is WriteOutcome.Verified)
    }
}
