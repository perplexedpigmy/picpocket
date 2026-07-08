package com.docscanner.data.repository

import com.docscanner.data.model.Document
import com.docscanner.data.model.Page
import com.docscanner.data.model.Tag
import com.docscanner.data.model.TagAutomation
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun observeDocuments(): Flow<List<Document>>
    fun observeDocument(documentId: Long): Flow<Document?>
    fun observePages(documentId: Long): Flow<List<Page>>
    suspend fun getDocument(documentId: Long): Document?
    suspend fun getPages(documentId: Long): List<Page>
    suspend fun getPage(pageId: Long): Page?
    suspend fun createDocument(name: String): Long
    suspend fun addPage(documentId: Long, imageUri: String, filterTypeOrdinal: Int = 0, fileSizeBytes: Long = 0): Long
    suspend fun updatePageOcrText(pageId: Long, ocrText: String)
    suspend fun updateDocumentName(documentId: Long, name: String)
    suspend fun updateDocumentOutputUri(documentId: Long, uri: String)
    suspend fun getDocumentsByName(name: String): List<Document>
    suspend fun deleteDocumentsByName(name: String)
    suspend fun deleteDocuments(documentIds: List<Long>)
    suspend fun deleteDocument(documentId: Long)
    suspend fun deletePage(pageId: Long)
    suspend fun reorderPages(documentId: Long, pageIds: List<Long>)
    suspend fun searchDocumentsByOcrText(query: String): Set<Long>

    fun observeAllTags(): Flow<List<Tag>>
    fun observeDocumentTags(documentId: Long): Flow<List<Tag>>
    fun observeDocumentTagMap(): Flow<Map<Long, List<Tag>>>
    fun searchTags(query: String): Flow<List<Tag>>
    suspend fun createTag(name: String): Long
    suspend fun renameTag(tagId: Long, name: String)
    suspend fun deleteTags(tagIds: List<Long>)
    suspend fun setDocumentTags(documentId: Long, tagIds: List<Long>)

    fun observeTagAutomations(tagId: Long): Flow<List<TagAutomation>>
    suspend fun getAutomationsForTagIds(tagIds: List<Long>): List<TagAutomation>
    suspend fun createAutomation(automation: TagAutomation): Long
    suspend fun deleteAutomation(id: Long)
}
