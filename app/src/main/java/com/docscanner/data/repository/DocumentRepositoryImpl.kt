package com.docscanner.data.repository

import com.docscanner.data.local.dao.DocumentDao
import com.docscanner.data.local.dao.DocumentStats
import com.docscanner.data.local.dao.PageDao
import com.docscanner.data.local.entity.DocumentEntity
import com.docscanner.data.local.entity.PageEntity
import com.docscanner.data.model.Document
import com.docscanner.data.model.Page
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val pageDao: PageDao,
) : DocumentRepository {

    override fun observeDocuments(): Flow<List<Document>> {
        return documentDao.observeAllWithStats().map { stats ->
            stats.map { it.toDomain() }
        }
    }

    override fun observeDocument(documentId: Long): Flow<Document?> {
        return documentDao.observeByIdWithStats(documentId).map { it?.toDomain() }
    }

    override fun observePages(documentId: Long): Flow<List<Page>> {
        return pageDao.observeByDocumentId(documentId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getDocument(documentId: Long): Document? {
        return documentDao.getByIdWithStats(documentId)?.toDomain()
    }

    override suspend fun getPages(documentId: Long): List<Page> {
        return pageDao.getByDocumentId(documentId).map { it.toDomain() }
    }

    override suspend fun getPage(pageId: Long): Page? {
        return pageDao.getById(pageId)?.toDomain()
    }

    override suspend fun createDocument(name: String): Long {
        val now = System.currentTimeMillis()
        return documentDao.insert(
            DocumentEntity(name = name, createdAt = now, updatedAt = now)
        )
    }

    override suspend fun addPage(
        documentId: Long,
        imageUri: String,
        filterTypeOrdinal: Int,
        fileSizeBytes: Long,
    ): Long {
        val maxPage = pageDao.maxPageNumber(documentId) ?: 0
        return pageDao.insert(
            PageEntity(
                documentId = documentId,
                pageNumber = maxPage + 1,
                imageUri = imageUri,
                filterTypeOrdinal = filterTypeOrdinal,
                fileSizeBytes = fileSizeBytes,
            )
        )
    }

    override suspend fun updatePageOcrText(pageId: Long, ocrText: String) {
        val page = pageDao.getById(pageId) ?: return
        pageDao.update(page.copy(ocrText = ocrText))
    }

    override suspend fun updateDocumentName(documentId: Long, name: String) {
        documentDao.updateName(documentId, name)
    }

    override suspend fun updateDocumentOutputUri(documentId: Long, uri: String) {
        documentDao.updateOutputUri(documentId, uri)
    }

    override suspend fun deleteDocuments(documentIds: List<Long>) {
        documentDao.deleteByIds(documentIds)
    }

    override suspend fun deleteDocument(documentId: Long) {
        documentDao.deleteByIds(listOf(documentId))
    }

    override suspend fun deletePage(pageId: Long) {
        val page = pageDao.getById(pageId) ?: return
        pageDao.delete(page)
    }

    override suspend fun reorderPages(documentId: Long, pageIds: List<Long>) {
        val pages = pageDao.getByDocumentId(documentId)
        for (page in pages) {
            val newOrder = pageIds.indexOf(page.id)
            if (newOrder >= 0) {
                pageDao.update(page.copy(pageNumber = newOrder))
            }
        }
    }

    private fun DocumentEntity.toDomain(): Document {
        return Document(
            id = id,
            name = name,
            outputUri = outputUri,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun DocumentStats.toDomain(): Document {
        return Document(
            id = id,
            name = name,
            outputUri = outputUri,
            createdAt = createdAt,
            updatedAt = updatedAt,
            pageCount = pageCount,
            totalFileSize = totalFileSize,
        )
    }

    private fun PageEntity.toDomain(): Page {
        return Page(
            id = id,
            documentId = documentId,
            pageNumber = pageNumber,
            imageUri = imageUri,
            ocrText = ocrText,
            filterTypeOrdinal = filterTypeOrdinal,
            createdAt = createdAt,
        )
    }
}
