package com.docscanner.data.repository

import com.docscanner.data.model.Document
import com.docscanner.data.model.Page
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
    suspend fun deleteDocuments(documentIds: List<Long>)
    suspend fun deleteDocument(documentId: Long)
    suspend fun deletePage(pageId: Long)
    suspend fun reorderPages(documentId: Long, pageIds: List<Long>)
    suspend fun searchDocumentsByOcrText(query: String): Set<Long>
}
