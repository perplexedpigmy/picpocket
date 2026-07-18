package com.docscanner.data.repository

import com.docscanner.data.model.Document
import com.docscanner.data.model.DocumentId
import com.docscanner.data.model.Page
import com.docscanner.data.model.Tag
import com.docscanner.data.model.TagAutomation
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun observeDocuments(): Flow<List<Document>>
    fun observeDocument(documentId: DocumentId): Flow<Document?>
    fun observePages(documentId: DocumentId): Flow<List<Page>>
    suspend fun getDocument(documentId: DocumentId): Document?
    suspend fun getPages(documentId: DocumentId): List<Page>
    suspend fun createDocument(name: String, qualityTier: Int = 0, pageSize: String? = null): DocumentId
    suspend fun addPage(documentId: DocumentId, imageUri: String, filterTypeOrdinal: Int = 0, fileSizeBytes: Long = 0, qualityTier: Int = 0)
    suspend fun updatePageOcrText(documentId: DocumentId, pageNumber: Int, ocrText: String)
    suspend fun updateDocumentName(documentId: DocumentId, name: String)
    suspend fun getDocumentsByName(name: String): List<Document>
    suspend fun getAllDocuments(): List<Document>
    suspend fun deleteDocumentsByName(name: String)
    suspend fun deleteDocuments(documentIds: List<DocumentId>)
    suspend fun deleteDocument(documentId: DocumentId)
    suspend fun deletePage(documentId: DocumentId, pageNumber: Int)
    suspend fun replacePages(documentId: DocumentId, keptFilenames: List<String>)
    suspend fun reorderPages(documentId: DocumentId, pageNumbers: List<Int>)
    suspend fun searchDocumentsByOcrText(query: String): Set<DocumentId>

    fun observeAllTags(): Flow<List<Tag>>
    fun observeDocumentTags(documentId: DocumentId): Flow<List<Tag>>
    fun observeDocumentTagMap(): Flow<Map<DocumentId, List<Tag>>>
    fun searchTags(query: String): Flow<List<Tag>>
    suspend fun createTag(name: String): Long
    suspend fun renameTag(tagId: Long, name: String)
    suspend fun deleteTags(tagIds: List<Long>)
    suspend fun setDocumentTags(documentId: DocumentId, tagIds: List<Long>)

    fun observeTagAutomations(tagId: Long): Flow<List<TagAutomation>>
    suspend fun getAutomationsForTagIds(tagIds: List<Long>): List<TagAutomation>
    suspend fun createAutomation(automation: TagAutomation): Long
    suspend fun deleteAutomation(id: Long)
}
