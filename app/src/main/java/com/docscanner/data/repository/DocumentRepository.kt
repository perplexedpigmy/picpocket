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
    suspend fun getDocument(documentId: DocumentId): Result<Document>
    suspend fun getPages(documentId: DocumentId): Result<List<Page>>
    suspend fun createDocument(name: String, qualityTier: Int = 0, pageSize: String? = null): Result<DocumentId>
    suspend fun addPage(documentId: DocumentId, imageUri: String, filterTypeOrdinal: Int = 0, fileSizeBytes: Long = 0, qualityTier: Int = 0): Result<Unit>
    suspend fun updatePageOcrText(documentId: DocumentId, pageNumber: Int, ocrText: String): Result<Unit>
    suspend fun updateDocumentName(documentId: DocumentId, name: String): Result<Unit>
    suspend fun getDocumentsByName(name: String): Result<List<Document>>
    suspend fun getAllDocuments(): Result<List<Document>>
    suspend fun deleteDocumentsByName(name: String): Result<Unit>
    suspend fun deleteDocuments(documentIds: List<DocumentId>): Result<Unit>
    suspend fun deleteDocument(documentId: DocumentId): Result<Unit>
    suspend fun deletePage(documentId: DocumentId, pageNumber: Int): Result<Unit>
    suspend fun replacePages(documentId: DocumentId, keptFilenames: List<String>): Result<Unit>
    suspend fun reorderPages(documentId: DocumentId, pageNumbers: List<Int>): Result<Unit>
    suspend fun searchDocumentsByOcrText(query: String): Result<Set<DocumentId>>

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
