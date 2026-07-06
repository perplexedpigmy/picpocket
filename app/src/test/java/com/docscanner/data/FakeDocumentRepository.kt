package com.docscanner.data

import com.docscanner.data.model.Document
import com.docscanner.data.model.Page
import com.docscanner.data.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeDocumentRepository : DocumentRepository {

    private val documents = MutableStateFlow<List<Document>>(emptyList())
    private val pages = mutableMapOf<Long, MutableStateFlow<List<Page>>>()
    private var nextDocId = 1L
    private var nextPageId = 1L

    override fun observeDocuments(): Flow<List<Document>> = documents

    override fun observeDocument(documentId: Long): Flow<Document?> {
        return documents.map { list -> list.find { it.id == documentId } }
    }

    override fun observePages(documentId: Long): Flow<List<Page>> {
        return pages.getOrPut(documentId) { MutableStateFlow(emptyList()) }
    }

    override suspend fun getDocument(documentId: Long): Document? {
        return documents.value.find { it.id == documentId }
    }

    override suspend fun getPages(documentId: Long): List<Page> {
        return pages[documentId]?.value ?: emptyList()
    }

    override suspend fun getPage(pageId: Long): Page? {
        return pages.values.flatMap { it.value }.find { it.id == pageId }
    }

    override suspend fun createDocument(name: String): Long {
        val now = System.currentTimeMillis()
        val id = nextDocId++
        val doc = Document(id, name, null, now, now)
        documents.value = documents.value + doc
        return id
    }

    override suspend fun addPage(
        documentId: Long,
        imageUri: String,
        filterTypeOrdinal: Int,
        fileSizeBytes: Long,
    ): Long {
        val id = nextPageId++
        val now = System.currentTimeMillis()
        val existingPages = pages.getOrPut(documentId) { MutableStateFlow(emptyList()) }
        val page = Page(
            id, documentId, existingPages.value.size + 1, imageUri,
            null, filterTypeOrdinal, now,
        )
        existingPages.value = existingPages.value + page
        return id
    }

    override suspend fun updatePageOcrText(pageId: Long, ocrText: String) {
        for ((_, state) in pages) {
            state.value = state.value.map {
                if (it.id == pageId) it.copy(ocrText = ocrText) else it
            }
        }
    }

    override suspend fun updateDocumentName(documentId: Long, name: String) {
        documents.value = documents.value.map {
            if (it.id == documentId) it.copy(name = name) else it
        }
    }

    override suspend fun updateDocumentOutputUri(documentId: Long, uri: String) {
        val now = System.currentTimeMillis()
        documents.value = documents.value.map {
            if (it.id == documentId) it.copy(outputUri = uri, updatedAt = now) else it
        }
    }

    override suspend fun deleteDocuments(documentIds: List<Long>) {
        documents.value = documents.value.filter { it.id !in documentIds }
        for (id in documentIds) {
            pages.remove(id)
        }
    }

    override suspend fun deleteDocument(documentId: Long) {
        deleteDocuments(listOf(documentId))
    }

    override suspend fun deletePage(pageId: Long) {
        for ((_, state) in pages) {
            state.value = state.value.filter { it.id != pageId }
        }
    }

    override suspend fun reorderPages(documentId: Long, pageIds: List<Long>) {
        val state = pages[documentId] ?: return
        state.value = state.value.map { page ->
            val newOrder = pageIds.indexOf(page.id)
            if (newOrder >= 0) page.copy(pageNumber = newOrder) else page
        }
    }

    override suspend fun searchDocumentsByOcrText(query: String): Set<Long> {
        val regex = try { Regex(query, RegexOption.IGNORE_CASE) } catch (_: Exception) { return emptySet() }
        return pages.values.flatMap { it.value }
            .filter { it.ocrText != null && regex.containsMatchIn(it.ocrText!!) }
            .map { it.documentId }
            .toSet()
    }
}
