package com.picpocket.app.drive.sync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.drive.EncryptionManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

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
        val result = manager.readMetadataJson("content://invalid-tree-uri/", "doc-123", "metadata.0.0.json")
        assert(result == null)
    }

    @Test
    fun `writeFile overwrites existing file in place and verifies`() = runTest {
        val fileName = "page_001.jpg"
        val data = byteArrayOf(1, 2, 3, 4)
        val expectedSize = encryptionManager.encrypt(data).size.toLong()

        val outStream = ByteArrayOutputStream()
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        every { contentResolver.openOutputStream(any(), "rwt") } returns outStream

        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.contentResolver } returns contentResolver

        val existingFile = mockk<DocumentFile>()
        every { existingFile.length() } returns expectedSize
        every { existingFile.uri } returns Uri.parse("content://test/doc/page_001.jpg")

        val docFolder = mockk<DocumentFile>()
        every { docFolder.findFile(fileName) } returns existingFile
        every { docFolder.uri } returns Uri.parse("content://test/doc")

        val root = mockk<DocumentFile>()
        every { root.findFile("doc-123") } returns docFolder

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(mockContext, Uri.parse("content://test/tree")) } returns root

        val manager = DriveFileManager(mockContext, encryptionManager)
        val result = manager.writeFile("content://test/tree", "doc-123", fileName, data)

        assert(result is WriteOutcome.Verified)
        assertTrue(outStream.size().toLong() == expectedSize)
        coVerify(exactly = 0) { docFolder.createFile(any(), any()) }
    }

    @Test
    fun `writeFile creates new file when none exists and verifies`() = runTest {
        val fileName = "page_001.jpg"
        val data = byteArrayOf(5, 6, 7)
        val expectedSize = encryptionManager.encrypt(data).size.toLong()

        val outStream = ByteArrayOutputStream()
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        every { contentResolver.openOutputStream(any()) } returns outStream

        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.contentResolver } returns contentResolver

        val newFile = mockk<DocumentFile>()
        every { newFile.length() } returns expectedSize
        every { newFile.uri } returns Uri.parse("content://test/doc/page_001.jpg")

        val docFolder = mockk<DocumentFile>()
        every { docFolder.findFile(fileName) } returnsMany listOf(null, newFile, newFile)
        every { docFolder.createFile(any(), fileName) } returns newFile
        every { docFolder.uri } returns Uri.parse("content://test/doc")

        val root = mockk<DocumentFile>()
        every { root.findFile("doc-123") } returns docFolder

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(mockContext, Uri.parse("content://test/tree")) } returns root

        val manager = DriveFileManager(mockContext, encryptionManager)
        val result = manager.writeFile("content://test/tree", "doc-123", fileName, data)

        assert(result is WriteOutcome.Verified)
        coVerify { docFolder.createFile(any(), fileName) }
    }

    @Test
    fun `writeFile returns Failed when openOutputStream is null`() = runTest {
        val fileName = "page_001.jpg"
        val data = byteArrayOf(1, 2, 3)

        val contentResolver = mockk<ContentResolver>(relaxed = true)
        every { contentResolver.openOutputStream(any()) } returns null

        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.contentResolver } returns contentResolver

        val newFile = mockk<DocumentFile>()
        every { newFile.length() } returns 0L
        every { newFile.uri } returns Uri.parse("content://test/doc/page_001.jpg")

        val docFolder = mockk<DocumentFile>()
        every { docFolder.findFile(fileName) } returns null
        every { docFolder.createFile(any(), fileName) } returns newFile
        every { docFolder.uri } returns Uri.parse("content://test/doc")

        val root = mockk<DocumentFile>()
        every { root.findFile("doc-123") } returns docFolder

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(mockContext, Uri.parse("content://test/tree")) } returns root

        val manager = DriveFileManager(mockContext, encryptionManager)
        val result = manager.writeFile("content://test/tree", "doc-123", fileName, data)

        assert(result is WriteOutcome.Failed)
    }

    @Test
    fun `writeFile deletes the created file when all retries fail`() = runTest {
        val fileName = "page_001.jpg"
        val data = byteArrayOf(1, 2, 3)

        val contentResolver = mockk<ContentResolver>(relaxed = true)
        every { contentResolver.openOutputStream(any()) } returns null

        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.contentResolver } returns contentResolver

        val newFile = mockk<DocumentFile>()
        every { newFile.delete() } returns true
        every { newFile.uri } returns Uri.parse("content://test/doc/page_001.jpg")

        val docFolder = mockk<DocumentFile>()
        every { docFolder.findFile(fileName) } returnsMany listOf(null, null, null, newFile)
        every { docFolder.createFile(any(), fileName) } returns newFile
        every { docFolder.uri } returns Uri.parse("content://test/doc")

        val root = mockk<DocumentFile>()
        every { root.findFile("doc-123") } returns docFolder

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(mockContext, Uri.parse("content://test/tree")) } returns root

        val manager = DriveFileManager(mockContext, encryptionManager)
        val result = manager.writeFile("content://test/tree", "doc-123", fileName, data)

        assert(result is WriteOutcome.Failed)
        coVerify(exactly = 1) { newFile.delete() }
    }
}
