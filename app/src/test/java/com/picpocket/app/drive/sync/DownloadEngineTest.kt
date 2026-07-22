package com.picpocket.app.drive.sync

import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.data.store.StoredPage
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DownloadEngineTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val driveFileManager = mockk<DriveFileManager>()
    private val documentStore = mockk<DocumentStore>()
    private val localDriveIndex = mockk<LocalDriveIndex>()

    private lateinit var engine: DownloadEngine

    @Before
    fun setUp() {
        engine = DownloadEngine(driveFileManager, documentStore, localDriveIndex)
    }

    @Test
    fun `checkFiles returns empty when all files present`() = runTest {
        coEvery { driveFileManager.listFileNames("content://tree/", "doc-1") } returns
            listOf("page_001.jpg", "page_002.jpg", "metadata.json")

        val doc = StoredDocument(
            id = "doc-1",
            name = "Test",
            createdAt = 0L,
            updatedAt = 0L,
            pages = mutableListOf(
                StoredPage(pageNumber = 1, filename = "page_001.jpg", createdAt = 0L),
                StoredPage(pageNumber = 2, filename = "page_002.jpg", createdAt = 0L),
            ),
        )

        val missing = engine.checkFiles("content://tree/", "doc-1", doc)
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `checkFiles reports missing page`() = runTest {
        coEvery { driveFileManager.listFileNames("content://tree/", "doc-1") } returns
            listOf("page_001.jpg", "metadata.json")

        val doc = StoredDocument(
            id = "doc-1",
            name = "Test",
            createdAt = 0L,
            updatedAt = 0L,
            pages = mutableListOf(
                StoredPage(pageNumber = 1, filename = "page_001.jpg", createdAt = 0L),
                StoredPage(pageNumber = 2, filename = "page_002.jpg", createdAt = 0L),
            ),
        )

        val missing = engine.checkFiles("content://tree/", "doc-1", doc)
        assertEquals(listOf("page_002.jpg"), missing)
    }

    @Test
    fun `checkFiles reports missing metadata`() = runTest {
        coEvery { driveFileManager.listFileNames("content://tree/", "doc-1") } returns
            listOf("page_001.jpg")

        val doc = StoredDocument(
            id = "doc-1",
            name = "Test",
            createdAt = 0L,
            updatedAt = 0L,
            pages = mutableListOf(
                StoredPage(pageNumber = 1, filename = "page_001.jpg", createdAt = 0L),
            ),
        )

        val missing = engine.checkFiles("content://tree/", "doc-1", doc)
        assertEquals(listOf("metadata.json"), missing)
    }

    @Test
    fun `checkFiles reports missing page and metadata`() = runTest {
        coEvery { driveFileManager.listFileNames("content://tree/", "doc-1") } returns
            emptyList()

        val doc = StoredDocument(
            id = "doc-1",
            name = "Test",
            createdAt = 0L,
            updatedAt = 0L,
            pages = mutableListOf(
                StoredPage(pageNumber = 1, filename = "page_001.jpg", createdAt = 0L),
            ),
        )

        val missing = engine.checkFiles("content://tree/", "doc-1", doc)
        assertEquals(listOf("page_001.jpg", "metadata.json"), missing)
    }

    @Test
    fun `checkFiles returns metadata when doc has no pages`() = runTest {
        coEvery { driveFileManager.listFileNames("content://tree/", "doc-1") } returns
            emptyList()

        val doc = StoredDocument(
            id = "doc-1",
            name = "Empty",
            createdAt = 0L,
            updatedAt = 0L,
        )

        val missing = engine.checkFiles("content://tree/", "doc-1", doc)
        assertEquals(listOf("metadata.json"), missing)
    }
}
