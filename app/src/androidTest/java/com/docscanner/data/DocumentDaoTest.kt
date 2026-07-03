package com.docscanner.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docscanner.data.local.DocScannerDatabase
import com.docscanner.data.local.entity.DocumentEntity
import com.docscanner.data.local.entity.PageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentDaoTest {

    private lateinit var database: DocScannerDatabase
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            context, DocScannerDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndReadDocument() = runBlocking {
        val doc = DocumentEntity(name = "Test Doc")
        val id = database.documentDao().insert(doc)
        assertTrue(id > 0)

        val loaded = database.documentDao().getById(id)
        assertNotNull(loaded)
        assertEquals("Test Doc", loaded?.name)
    }

    @Test
    fun observeAllDocuments() = runBlocking {
        database.documentDao().insert(DocumentEntity(name = "A"))
        database.documentDao().insert(DocumentEntity(name = "B"))

        val docs = database.documentDao().observeAll().first()
        assertEquals(2, docs.size)
    }

    @Test
    fun updateDocumentName() = runBlocking {
        val id = database.documentDao().insert(DocumentEntity(name = "Old"))
        database.documentDao().updateName(id, "New")

        val doc = database.documentDao().getById(id)
        assertEquals("New", doc?.name)
    }

    @Test
    fun deleteDocumentCascadesToPages() = runBlocking {
        val docId = database.documentDao().insert(DocumentEntity(name = "Doc"))
        database.pageDao().insert(PageEntity(documentId = docId, pageNumber = 1, imageUri = "uri"))
        database.pageDao().insert(PageEntity(documentId = docId, pageNumber = 2, imageUri = "uri"))

        database.documentDao().deleteByIds(listOf(docId))

        val pages = database.pageDao().getByDocumentId(docId)
        assertTrue(pages.isEmpty())
    }

    @Test
    fun deleteMultipleDocuments() = runBlocking {
        val id1 = database.documentDao().insert(DocumentEntity(name = "A"))
        val id2 = database.documentDao().insert(DocumentEntity(name = "B"))
        val id3 = database.documentDao().insert(DocumentEntity(name = "C"))

        database.documentDao().deleteByIds(listOf(id1, id3))

        assertNull(database.documentDao().getById(id1))
        assertNotNull(database.documentDao().getById(id2))
        assertNull(database.documentDao().getById(id3))
    }
}
