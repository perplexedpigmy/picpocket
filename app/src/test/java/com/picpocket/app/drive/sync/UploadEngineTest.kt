package com.picpocket.app.drive.sync

import android.content.Context
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.data.store.StoredPage
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class UploadEngineTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val driveFileManager = mockk<DriveFileManager>()
    private val documentStore = mockk<DocumentStore>()
    private val localDriveIndex = mockk<LocalDriveIndex>()
    private val context = mockk<Context>()

    private lateinit var uploadEngine: UploadEngine

    @Before
    fun setUp() {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.setDocumentInfo(any(), any()) } returns Unit
        uploadEngine = UploadEngine(driveFileManager, documentStore, localDriveIndex, context)
    }

    private fun doc(id: String, pages: List<StoredPage>): StoredDocument =
        StoredDocument(id = id, name = "Doc", createdAt = 0L, updatedAt = 0L, pages = pages.toMutableList())

    private fun tempPage(): File {
        val f = File.createTempFile("test", ".jpg")
        f.writeBytes(byteArrayOf(1, 2, 3))
        return f
    }

    @Test
    fun `uploadDocument returns false and does not bump version when page write fails`() = runTest {
        val d = doc("doc-1", listOf(StoredPage(pageNumber = 1, filename = "page_001.jpg", createdAt = 0L)))
        coEvery { driveFileManager.createDocFolder("content://tree/", "doc-1") } returns true
        every { documentStore.pageFile("doc-1", "page_001.jpg") } returns tempPage()
        coEvery { driveFileManager.writeFile(any(), any(), any(), any()) } returns WriteOutcome.Failed("boom")
        every { localDriveIndex.getDocumentInfo("doc-1") } returns DocumentDriveInfo()

        val result = uploadEngine.uploadDocument(d)

        assertFalse(result)
        coVerify(inverse = true) { localDriveIndex.setDocumentInfo(any(), any()) }
    }

    @Test
    fun `uploadDocument returns false and does not bump version when metadata write fails`() = runTest {
        val d = doc("doc-1", emptyList())
        coEvery { driveFileManager.createDocFolder(any(), any()) } returns true
        coEvery { driveFileManager.writeFile(any(), any(), eq("metadata.json"), any()) } returns WriteOutcome.Failed("meta boom")
        every { localDriveIndex.getDocumentInfo("doc-1") } returns DocumentDriveInfo()

        val result = uploadEngine.uploadDocument(d)

        assertFalse(result)
        coVerify(inverse = true) { localDriveIndex.setDocumentInfo(any(), any()) }
    }

    @Test
    fun `uploadDocument returns true and bumps version only when all writes verified`() = runTest {
        val d = doc("doc-1", listOf(StoredPage(pageNumber = 1, filename = "page_001.jpg", createdAt = 0L)))
        coEvery { driveFileManager.createDocFolder(any(), any()) } returns true
        every { documentStore.pageFile("doc-1", "page_001.jpg") } returns tempPage()
        coEvery { driveFileManager.writeFile(any(), any(), any(), any()) } returns WriteOutcome.Verified
        val info = DocumentDriveInfo()
        every { localDriveIndex.getDocumentInfo("doc-1") } returns info

        val result = uploadEngine.uploadDocument(d)

        assertTrue(result)
        assertTrue(info.syncVersion == 1)
        coVerify { localDriveIndex.setDocumentInfo("doc-1", info) }
    }
}
