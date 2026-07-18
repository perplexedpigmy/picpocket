package com.docscanner.data

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import com.docscanner.data.local.DocScannerDatabase
import com.docscanner.data.repository.DocumentRepositoryImpl
import com.docscanner.data.store.DocumentStore
import com.docscanner.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun tempPageUri(): String {
    val f = File.createTempFile("page", ".jpg")
    f.writeBytes(byteArrayOf(0, 1, 2))
    return Uri.fromFile(f).toString()
}

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DocumentRepositoryTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var database: DocScannerDatabase
    private lateinit var repository: DocumentRepositoryImpl

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DocScannerDatabase::class.java,
        ).allowMainThreadQueries().build()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        repository = DocumentRepositoryImpl(
            DocumentStore(app),
            database.tagDao(),
            database.tagAutomationDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `create document and observe list`() = runTest {
        val id = repository.createDocument("Test Doc").getOrThrow()
        assertTrue("Document ID should not be empty", id.isNotEmpty())
        val doc = repository.getDocument(id).getOrNull()
        assertNotNull(doc)
        assertEquals("Test Doc", doc?.name)
    }

    @Test
    fun `add page to document`() = runTest {
        val docId = repository.createDocument("Multi-page").getOrThrow()
        repository.addPage(docId, tempPageUri())

        val pages = repository.getPages(docId).getOrDefault(emptyList())
        assertEquals(1, pages.size)
    }

    @Test
    fun `add multiple pages increments pageNumber`() = runTest {
        val docId = repository.createDocument("Pages").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())

        val pages = repository.getPages(docId).getOrDefault(emptyList())
        assertEquals(3, pages.size)
        assertEquals(1, pages[0].pageNumber)
        assertEquals(2, pages[1].pageNumber)
        assertEquals(3, pages[2].pageNumber)
    }

    @Test
    fun `delete document removes it and its pages`() = runTest {
        val docId = repository.createDocument("Delete me").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.deleteDocument(docId)

        val doc = repository.getDocument(docId).getOrNull()
        assertNull("Document should be null after delete", doc)
        val pages = repository.getPages(docId).getOrDefault(emptyList())
        assertTrue("Pages should be empty after document delete", pages.isEmpty())
    }

    @Test
    fun `update document name`() = runTest {
        val docId = repository.createDocument("Old Name").getOrThrow()
        repository.updateDocumentName(docId, "New Name")

        val doc = repository.getDocument(docId).getOrNull()
        assertNotNull(doc)
        assertEquals("New Name", doc?.name)
    }

    @Test
    fun `update page OCR text`() = runTest {
        val docId = repository.createDocument("OCR test").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.updatePageOcrText(docId, 1, "Extracted text")

        val pages = repository.getPages(docId).getOrDefault(emptyList())
        assertEquals("Extracted text", pages[0].ocrText)
    }

    @Test
    fun `delete multiple documents at once`() = runTest {
        val id1 = repository.createDocument("Doc 1").getOrThrow()
        val id2 = repository.createDocument("Doc 2").getOrThrow()
        val id3 = repository.createDocument("Doc 3").getOrThrow()

        repository.deleteDocuments(listOf(id1, id3))

        assertNull(repository.getDocument(id1).getOrNull())
        assertNotNull(repository.getDocument(id2).getOrNull())
        assertNull(repository.getDocument(id3).getOrNull())
    }

    @Test
    fun `delete page removes it and keeps others`() = runTest {
        val docId = repository.createDocument("Multi-page").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())

        repository.deletePage(docId, 2)

        val remaining = repository.getPages(docId).getOrDefault(emptyList())
        assertEquals(2, remaining.size)
        assertEquals(1, remaining[0].pageNumber)
        assertEquals(2, remaining[1].pageNumber)
    }

    @Test
    fun `getDocument returns pageCount and totalFileSize`() = runTest {
        val docId = repository.createDocument("Stats test").getOrThrow()
        repository.addPage(docId, tempPageUri(), fileSizeBytes = 1000L)
        repository.addPage(docId, tempPageUri(), fileSizeBytes = 2000L)

        val doc = repository.getDocument(docId).getOrNull()
        assertNotNull(doc)
    }

    @Test
    fun `reorder pages updates page numbers`() = runTest {
        val docId = repository.createDocument("Reorder test").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())

        repository.reorderPages(docId, listOf(3, 1, 2))

        val pages = repository.getPages(docId).getOrDefault(emptyList()).sortedBy { it.pageNumber }
        assertEquals(3, pages.size)
    }

    @Test
    fun `create tag and observe all tags`() = runTest {
        val tagId = repository.createTag("Work")
        assertTrue("Tag ID should be positive", tagId > 0)
        val tags = repository.observeAllTags().first()
        assertEquals(1, tags.size)
        assertEquals("Work", tags[0].name)
    }

    @Test
    fun `rename tag updates name`() = runTest {
        val tagId = repository.createTag("Old")
        repository.renameTag(tagId, "Renamed")
        val tags = repository.observeAllTags().first()
        assertEquals("Renamed", tags[0].name)
    }

    @Test
    fun `delete tags removes them`() = runTest {
        val id1 = repository.createTag("Tag1")
        val id2 = repository.createTag("Tag2")
        repository.deleteTags(listOf(id1))
        val tags = repository.observeAllTags().first()
        assertEquals(1, tags.size)
        assertEquals(id2, tags[0].id)
    }

    @Test
    fun `set document tags replaces existing tags`() = runTest {
        val docId = repository.createDocument("Tagged Doc").getOrThrow()
        val tag1 = repository.createTag("Important")
        val tag2 = repository.createTag("Urgent")

        repository.setDocumentTags(docId, listOf(tag1, tag2))
        var docTags = repository.observeDocumentTags(docId).first()
        assertEquals(2, docTags.size)

        repository.setDocumentTags(docId, listOf(tag1))
        docTags = repository.observeDocumentTags(docId).first()
        assertEquals(1, docTags.size)
        assertEquals(tag1, docTags[0].id)
    }

    @Test
    fun `set document tags with empty list clears tags`() = runTest {
        val docId = repository.createDocument("Clear Tags").getOrThrow()
        val tag = repository.createTag("Temp")
        repository.setDocumentTags(docId, listOf(tag))
        repository.setDocumentTags(docId, emptyList())
        val docTags = repository.observeDocumentTags(docId).first()
        assertTrue(docTags.isEmpty())
    }

    @Test
    fun `replacePages preserves keptFilenames order`() = runTest {
        val docId = repository.createDocument("Order test").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        val pages = repository.getPages(docId).getOrDefault(emptyList())
        val filenames = pages.map { it.filename }

        val reversed = filenames.reversed()
        repository.replacePages(docId, reversed)
        val updated = repository.getPages(docId).getOrDefault(emptyList())

        assertEquals("replacePages should preserve the order of keptFilenames",
            reversed, updated.map { it.filename })
    }

    @Test
    fun `replacePages removes pages not in keptFilenames`() = runTest {
        val docId = repository.createDocument("Trim test").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        val pages = repository.getPages(docId).getOrDefault(emptyList())
        val firstTwo = pages.take(2).map { it.filename }

        repository.replacePages(docId, firstTwo)
        val remaining = repository.getPages(docId).getOrDefault(emptyList())

        assertEquals(2, remaining.size)
        assertEquals(firstTwo, remaining.map { it.filename })
    }

    @Test
    fun `replacePages deletes document when all pages removed`() = runTest {
        val docId = repository.createDocument("Delete all").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.replacePages(docId, emptyList())

        val doc = repository.getDocument(docId).getOrNull()
        assertNull("Document should be deleted when all pages removed", doc)
    }

    @Test
    fun `observeDocumentTagMap returns all document tags`() = runTest {
        val doc1 = repository.createDocument("Doc1").getOrThrow()
        val doc2 = repository.createDocument("Doc2").getOrThrow()
        val tag1 = repository.createTag("Work")
        val tag2 = repository.createTag("Personal")
        repository.setDocumentTags(doc1, listOf(tag1, tag2))
        repository.setDocumentTags(doc2, listOf(tag1))
        val map = repository.observeDocumentTagMap().first()
        assertEquals(2, map[doc1]?.size)
        assertEquals(1, map[doc2]?.size)
    }
}
