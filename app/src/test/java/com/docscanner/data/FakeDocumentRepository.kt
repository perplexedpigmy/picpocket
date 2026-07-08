package com.docscanner.data

import com.docscanner.data.model.Document
import com.docscanner.data.model.Page
import com.docscanner.data.model.Tag
import com.docscanner.data.model.TagAutomation
import com.docscanner.data.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeDocumentRepository : DocumentRepository {

    private val documents = MutableStateFlow<List<Document>>(emptyList())
    private val pages = mutableMapOf<Long, MutableStateFlow<List<Page>>>()
    private val tags = MutableStateFlow<List<Tag>>(emptyList())
    private val documentTags = mutableMapOf<Long, MutableStateFlow<List<Tag>>>()
    private val documentTagMap = MutableStateFlow<Map<Long, List<Tag>>>(emptyMap())
    private var nextDocId = 1L
    private var nextPageId = 1L
    private var nextTagId = 1L

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

    override suspend fun getDocumentsByName(name: String): List<Document> {
        return documents.value.filter { it.name == name }
    }

    override suspend fun deleteDocumentsByName(name: String) {
        val ids = documents.value.filter { it.name == name }.map { it.id }
        deleteDocuments(ids)
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

    override fun observeAllTags(): Flow<List<Tag>> = tags

    override fun observeDocumentTags(documentId: Long): Flow<List<Tag>> {
        return documentTags.getOrPut(documentId) { MutableStateFlow(emptyList()) }
    }

    override fun observeDocumentTagMap(): Flow<Map<Long, List<Tag>>> = documentTagMap

    override fun searchTags(query: String): Flow<List<Tag>> {
        return tags.map { list -> list.filter { it.name.contains(query, ignoreCase = true) } }
    }

    override suspend fun createTag(name: String): Long {
        val id = nextTagId++
        val color = ((tags.value.size) % 8)
        val tag = Tag(id, name, color)
        tags.value = tags.value + tag
        return id
    }

    override suspend fun renameTag(tagId: Long, name: String) {
        tags.value = tags.value.map { if (it.id == tagId) it.copy(name = name) else it }
    }

    override suspend fun deleteTags(tagIds: List<Long>) {
        tags.value = tags.value.filter { it.id !in tagIds }
        for ((_, state) in documentTags) {
            state.value = state.value.filter { it.id !in tagIds }
        }
        val map = mutableMapOf<Long, List<Tag>>()
        for ((id, state) in documentTags) {
            map[id] = state.value
        }
        documentTagMap.value = map
    }

    override suspend fun setDocumentTags(documentId: Long, tagIds: List<Long>) {
        val selected = tags.value.filter { it.id in tagIds }
        documentTags.getOrPut(documentId) { MutableStateFlow(emptyList()) }.value = selected
        val map = mutableMapOf<Long, List<Tag>>()
        for ((id, state) in documentTags) {
            map[id] = state.value
        }
        documentTagMap.value = map
    }

    private val tagAutomations = MutableStateFlow<List<TagAutomation>>(emptyList())

    override fun observeTagAutomations(tagId: Long): Flow<List<TagAutomation>> {
        return tagAutomations.map { list -> list.filter { it.tagId == tagId } }
    }

    override suspend fun getAutomationsForTagIds(tagIds: List<Long>): List<TagAutomation> {
        return tagAutomations.value.filter { it.tagId in tagIds }
    }

    override suspend fun createAutomation(automation: TagAutomation): Long {
        val id = tagAutomations.value.size.toLong() + 1
        tagAutomations.value = tagAutomations.value + automation.copy(id = id)
        return id
    }

    override suspend fun deleteAutomation(id: Long) {
        tagAutomations.value = tagAutomations.value.filter { it.id != id }
    }
}
