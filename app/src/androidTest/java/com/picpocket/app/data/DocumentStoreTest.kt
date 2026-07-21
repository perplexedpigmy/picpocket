package com.picpocket.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picpocket.app.data.store.DocumentStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentStoreTest {

    private lateinit var store: DocumentStore
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        store = DocumentStore(app)
    }

    @Test
    fun createAndReadDocument() = runBlocking {
        val doc = store.createDocument("Test Doc")
        assertNotNull(doc.id)
        assertEquals("Test Doc", doc.name)

        val loaded = store.readMetadata(doc.id)
        assertNotNull(loaded)
        assertEquals("Test Doc", loaded!!.name)
    }

    @Test
    fun observeAllDocuments() = runBlocking {
        store.createDocument("A")
        store.createDocument("B")

        val docs = store.listDocuments()
        assertEquals(2, docs.size)
    }

    @Test
    fun updateDocumentName() = runBlocking {
        val doc = store.createDocument("Old")
        store.updateDocumentName(doc.id, "New")

        val loaded = store.readMetadata(doc.id)
        assertEquals("New", loaded?.name)
    }

    @Test
    fun deleteDocumentRemovesPages() = runBlocking {
        val doc = store.createDocument("Doc")
        store.addPage(doc.id, pageNumber = 1, filename = "00001", fileSizeBytes = 100)
        store.addPage(doc.id, pageNumber = 2, filename = "00002", fileSizeBytes = 200)

        store.deleteDocument(doc.id)

        val loaded = store.readMetadata(doc.id)
        assertNull(loaded)
    }

    @Test
    fun deleteMultipleDocuments() = runBlocking {
        val doc1 = store.createDocument("A")
        val doc2 = store.createDocument("B")
        val doc3 = store.createDocument("C")

        store.deleteDocument(doc1.id)
        store.deleteDocument(doc3.id)

        assertNull(store.readMetadata(doc1.id))
        assertNotNull(store.readMetadata(doc2.id))
        assertNull(store.readMetadata(doc3.id))
    }
}
