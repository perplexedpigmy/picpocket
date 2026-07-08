package com.docscanner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.docscanner.data.local.DocScannerDatabase
import com.docscanner.data.repository.DocumentRepositoryImpl
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
        repository = DocumentRepositoryImpl(database.documentDao(), database.pageDao(), database.tagDao(), database.tagAutomationDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `create document and observe list`() = runTest {
        val id = repository.createDocument("Test Doc")
        assertTrue("Document ID should be positive", id > 0)
        val doc = repository.getDocument(id)
        assertNotNull(doc)
        assertEquals("Test Doc", doc?.name)
    }

    @Test
    fun `add page to document`() = runTest {
        val docId = repository.createDocument("Multi-page")
        val pageId = repository.addPage(docId, "content://test/page1.jpg")

        assertTrue("Page ID should be positive", pageId > 0)
        val pages = repository.getPages(docId)
        assertEquals(1, pages.size)
        assertEquals("content://test/page1.jpg", pages[0].imageUri)
    }

    @Test
    fun `add multiple pages increments pageNumber`() = runTest {
        val docId = repository.createDocument("Pages")
        repository.addPage(docId, "content://page1.jpg")
        repository.addPage(docId, "content://page2.jpg")
        repository.addPage(docId, "content://page3.jpg")

        val pages = repository.getPages(docId)
        assertEquals(3, pages.size)
        assertEquals(1, pages[0].pageNumber)
        assertEquals(2, pages[1].pageNumber)
        assertEquals(3, pages[2].pageNumber)
    }

    @Test
    fun `delete document removes it and its pages`() = runTest {
        val docId = repository.createDocument("Delete me")
        repository.addPage(docId, "content://page1.jpg")
        repository.deleteDocument(docId)

        val doc = repository.getDocument(docId)
        assertNull("Document should be null after delete", doc)
        val pages = repository.getPages(docId)
        assertTrue("Pages should be empty after document delete", pages.isEmpty())
    }

    @Test
    fun `update document name`() = runTest {
        val docId = repository.createDocument("Old Name")
        repository.updateDocumentName(docId, "New Name")

        val doc = repository.getDocument(docId)
        assertNotNull(doc)
        assertEquals("New Name", doc?.name)
    }

    @Test
    fun `update page OCR text`() = runTest {
        val docId = repository.createDocument("OCR test")
        val pageId = repository.addPage(docId, "content://page.jpg")
        repository.updatePageOcrText(pageId, "Extracted text")

        val page = repository.getPage(pageId)
        assertNotNull(page)
        assertEquals("Extracted text", page?.ocrText)
    }

    @Test
    fun `delete multiple documents at once`() = runTest {
        val id1 = repository.createDocument("Doc 1")
        val id2 = repository.createDocument("Doc 2")
        val id3 = repository.createDocument("Doc 3")

        repository.deleteDocuments(listOf(id1, id3))

        assertNull(repository.getDocument(id1))
        assertNotNull(repository.getDocument(id2))
        assertNull(repository.getDocument(id3))
    }

    @Test
    fun `delete page removes it and keeps others`() = runTest {
        val docId = repository.createDocument("Multi-page")
        repository.addPage(docId, "content://page1.jpg")
        val pageId = repository.addPage(docId, "content://page2.jpg")
        repository.addPage(docId, "content://page3.jpg")

        repository.deletePage(pageId)

        val remaining = repository.getPages(docId)
        assertEquals(2, remaining.size)
        assertTrue(remaining.none { it.id == pageId })
    }

    @Test
    fun `addPage stores fileSizeBytes`() = runTest {
        val docId = repository.createDocument("File size test")
        val pageId = repository.addPage(docId, "content://page.jpg", fileSizeBytes = 12345L)

        val page = repository.getPage(pageId)
        assertNotNull(page)
    }

    @Test
    fun `getDocument returns pageCount and totalFileSize`() = runTest {
        val docId = repository.createDocument("Stats test")
        repository.addPage(docId, "content://p1.jpg", fileSizeBytes = 1000L)
        repository.addPage(docId, "content://p2.jpg", fileSizeBytes = 2000L)

        val doc = repository.getDocument(docId)
        assertNotNull(doc)
        assertEquals(2, doc!!.pageCount)
        assertEquals(3000L, doc.totalFileSize)
    }

    @Test
    fun `reorder pages updates page numbers`() = runTest {
        val docId = repository.createDocument("Reorder test")
        val aId = repository.addPage(docId, "content://a.jpg")
        val bId = repository.addPage(docId, "content://b.jpg")
        val cId = repository.addPage(docId, "content://c.jpg")

        repository.reorderPages(docId, listOf(cId, aId, bId))

        val pages = repository.getPages(docId).sortedBy { it.pageNumber }
        assertEquals(3, pages.size)
        assertEquals(cId, pages[0].id)
        assertEquals(aId, pages[1].id)
        assertEquals(bId, pages[2].id)
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
        val docId = repository.createDocument("Tagged Doc")
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
        val docId = repository.createDocument("Clear Tags")
        val tag = repository.createTag("Temp")
        repository.setDocumentTags(docId, listOf(tag))
        repository.setDocumentTags(docId, emptyList())
        val docTags = repository.observeDocumentTags(docId).first()
        assertTrue(docTags.isEmpty())
    }

    @Test
    fun `observeDocumentTagMap returns all document tags`() = runTest {
        val doc1 = repository.createDocument("Doc1")
        val doc2 = repository.createDocument("Doc2")
        val tag1 = repository.createTag("Work")
        val tag2 = repository.createTag("Personal")
        repository.setDocumentTags(doc1, listOf(tag1, tag2))
        repository.setDocumentTags(doc2, listOf(tag1))
        val map = repository.observeDocumentTagMap().first()
        assertEquals(2, map[doc1]?.size)
        assertEquals(1, map[doc2]?.size)
    }
}
