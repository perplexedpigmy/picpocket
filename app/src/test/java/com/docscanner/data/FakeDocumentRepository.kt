package com.docscanner.data

import com.docscanner.data.model.Document
import com.docscanner.data.model.DocumentId
import com.docscanner.data.model.Page
import com.docscanner.data.model.Tag
import com.docscanner.data.model.TagAutomation
import com.docscanner.data.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeDocumentRepository : DocumentRepository {

    private val documents = MutableStateFlow<List<Document>>(emptyList())
    private val pageLists = mutableMapOf<DocumentId, MutableStateFlow<List<Page>>>()
    private val pageFilenames = mutableMapOf<DocumentId, MutableStateFlow<Map<Long, String>>>()
    private val tags = MutableStateFlow<List<Tag>>(emptyList())
    private val documentTags = mutableMapOf<DocumentId, MutableStateFlow<List<Tag>>>()
    private val documentTagMap = MutableStateFlow<Map<DocumentId, List<Tag>>>(emptyMap())
    private var nextDocId = 0
    private var nextTagId = 1L

    override fun observeDocuments(): Flow<List<Document>> = documents

    override fun observeDocument(documentId: DocumentId): Flow<Document?> {
        return documents.map { list -> list.find { it.id == documentId } }
    }

    override fun observePages(documentId: DocumentId): Flow<List<Page>> {
        return pageLists.getOrPut(documentId) { MutableStateFlow(emptyList()) }
    }

    override suspend fun getDocument(documentId: DocumentId): Document? {
        return documents.value.find { it.id == documentId }
    }

    override suspend fun getPages(documentId: DocumentId): List<Page> {
        return pageLists[documentId]?.value ?: emptyList()
    }

    override suspend fun createDocument(name: String, qualityTier: Int, pageSize: String?): DocumentId {
        val now = System.currentTimeMillis()
        nextDocId++
        val id = "doc_$nextDocId"
        val doc = Document(id, name, now, now, qualityTier = qualityTier, pageSize = pageSize)
        documents.value = documents.value + doc
        return id
    }

    override suspend fun addPage(
        documentId: DocumentId,
        imageUri: String,
        filterTypeOrdinal: Int,
        fileSizeBytes: Long,
        qualityTier: Int,
    ) {
        val now = System.currentTimeMillis()
        val existingPages = pageLists.getOrPut(documentId) { MutableStateFlow(emptyList()) }
        val pageNum = existingPages.value.size + 1
        val filename = "%05d".format(pageNum)
        val page = Page(
            id = pageNum.toLong(),
            documentId = documentId,
            pageNumber = pageNum,
            filename = filename,
            imageUri = imageUri,
            ocrText = null,
            filterTypeOrdinal = filterTypeOrdinal,
            createdAt = now,
        )
        existingPages.value = existingPages.value + page
        val filenames = pageFilenames.getOrPut(documentId) { MutableStateFlow(emptyMap()) }
        filenames.value = filenames.value + (page.id to filename)
    }

    override suspend fun updatePageOcrText(documentId: DocumentId, pageNumber: Int, ocrText: String) {
        val state = pageLists[documentId] ?: return
        state.value = state.value.map {
            if (it.pageNumber == pageNumber) it.copy(ocrText = ocrText) else it
        }
    }

    override suspend fun updateDocumentName(documentId: DocumentId, name: String) {
        documents.value = documents.value.map {
            if (it.id == documentId) it.copy(name = name) else it
        }
    }

    override suspend fun getDocumentsByName(name: String): List<Document> {
        return documents.value.filter { it.name == name }
    }

    override suspend fun getAllDocuments(): List<Document> {
        return documents.value
    }

    override suspend fun deleteDocumentsByName(name: String) {
        val ids = documents.value.filter { it.name == name }.map { it.id }
        deleteDocuments(ids)
    }

    override suspend fun deleteDocuments(documentIds: List<DocumentId>) {
        documents.value = documents.value.filter { it.id !in documentIds }
        for (id in documentIds) {
            pageLists.remove(id)
        }
    }

    override suspend fun deleteDocument(documentId: DocumentId) {
        deleteDocuments(listOf(documentId))
    }

    override suspend fun deletePage(documentId: DocumentId, pageNumber: Int) {
        val state = pageLists[documentId] ?: return
        state.value = state.value.filter { it.pageNumber != pageNumber }
        val filenames = pageFilenames[documentId]
        if (filenames != null) {
            val removed = filenames.value.filter { it.value == "%05d".format(pageNumber) }
            filenames.value = filenames.value - removed.keys
        }
    }

    override suspend fun replacePages(documentId: DocumentId, keptFilenames: List<String>) {
        val state = pageLists[documentId] ?: return
        val filenames = pageFilenames[documentId] ?: return
        val keptSet = keptFilenames.toSet()
        val keptIds = filenames.value.filter { it.value in keptSet }.keys
        val survivors = state.value.filter { it.id in keptIds }
        val newFilenames = survivors.mapIndexed { index, page ->
            page.id to "%05d".format(index + 1)
        }.toMap()
        val updated = survivors.mapIndexed { index, page ->
            page.copy(pageNumber = index + 1)
        }
        state.value = updated
        filenames.value = newFilenames
    }

    override suspend fun reorderPages(documentId: DocumentId, pageNumbers: List<Int>) {
        val state = pageLists[documentId] ?: return
        val pageMap = state.value.associateBy { it.pageNumber }
        val reordered = pageNumbers.mapNotNull { pageMap[it] }
        val updated = reordered.mapIndexed { index, page ->
            page.copy(pageNumber = index + 1)
        }
        state.value = updated
    }

    override suspend fun searchDocumentsByOcrText(query: String): Set<DocumentId> {
        val regex = try { Regex(query, RegexOption.IGNORE_CASE) } catch (_: Exception) { return emptySet() }
        return pageLists.entries.flatMap { (docId, state) ->
            state.value.filter { it.ocrText != null && regex.containsMatchIn(it.ocrText!!) }
                .map { docId }
        }.toSet()
    }

    override fun observeAllTags(): Flow<List<Tag>> = tags

    override fun observeDocumentTags(documentId: DocumentId): Flow<List<Tag>> {
        return documentTags.getOrPut(documentId) { MutableStateFlow(emptyList()) }
    }

    override fun observeDocumentTagMap(): Flow<Map<DocumentId, List<Tag>>> = documentTagMap

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
        val map = mutableMapOf<DocumentId, List<Tag>>()
        for ((id, state) in documentTags) {
            map[id] = state.value
        }
        documentTagMap.value = map
    }

    override suspend fun setDocumentTags(documentId: DocumentId, tagIds: List<Long>) {
        val selected = tags.value.filter { it.id in tagIds }
        documentTags.getOrPut(documentId) { MutableStateFlow(emptyList()) }.value = selected
        val map = mutableMapOf<DocumentId, List<Tag>>()
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
