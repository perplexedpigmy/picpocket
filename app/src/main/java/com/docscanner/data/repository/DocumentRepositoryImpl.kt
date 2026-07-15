package com.docscanner.data.repository

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import com.docscanner.data.local.dao.DocumentDao
import com.docscanner.data.local.dao.DocumentStats
import com.docscanner.data.local.dao.DocumentTagRow
import com.docscanner.data.local.dao.OcrTextRow
import com.docscanner.data.local.dao.PageDao
import com.docscanner.data.local.dao.TagAutomationDao
import com.docscanner.data.local.dao.TagDao
import com.docscanner.data.local.entity.DocumentEntity
import com.docscanner.data.local.entity.DocumentTagCrossRef
import com.docscanner.data.local.entity.PageEntity
import com.docscanner.data.local.entity.TagAutomationEntity
import com.docscanner.data.local.entity.TagEntity
import com.docscanner.data.local.entity.toDomain
import com.docscanner.data.local.entity.toEntity
import com.docscanner.data.model.Document
import com.docscanner.data.model.Page
import com.docscanner.data.model.Tag
import com.docscanner.data.model.TagAutomation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val app: Application,
    private val documentDao: DocumentDao,
    private val pageDao: PageDao,
    private val tagDao: TagDao,
    private val tagAutomationDao: TagAutomationDao,
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

    override suspend fun getDocumentsByName(name: String): List<Document> {
        return documentDao.findByName(name).map { it.toDomain() }
    }

    override suspend fun getAllDocuments(): List<Document> {
        return documentDao.getAll().map { it.toDomain() }
    }

    override suspend fun deleteDocumentsByName(name: String) {
        val docs = documentDao.findByName(name)
        for (doc in docs) deleteDocumentFiles(doc)
        documentDao.deleteByIds(docs.map { it.id })
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

    override suspend fun updatePageImageUri(pageId: Long, imageUri: String) {
        val page = pageDao.getById(pageId) ?: return
        pageDao.update(page.copy(imageUri = imageUri))
    }

    override suspend fun updateDocumentName(documentId: Long, name: String) {
        documentDao.updateName(documentId, name)
    }

    override suspend fun updateDocumentOutputUri(documentId: Long, uri: String) {
        documentDao.updateOutputUri(documentId, uri)
    }

    override suspend fun deleteDocuments(documentIds: List<Long>) {
        for (id in documentIds) {
            val doc = documentDao.getById(id)
            if (doc != null) deleteDocumentFiles(doc)
        }
        documentDao.deleteByIds(documentIds)
    }

    override suspend fun deleteDocument(documentId: Long) {
        deleteDocuments(listOf(documentId))
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

    override suspend fun searchDocumentsByOcrText(query: String): Set<Long> {
        val regex = try { Regex(query, RegexOption.IGNORE_CASE) } catch (_: Exception) { return emptySet() }
        return pageDao.getAllOcrTexts()
            .filter { regex.containsMatchIn(it.ocrText) }
            .map { it.documentId }
            .toSet()
    }

    override fun observeAllTags(): Flow<List<Tag>> {
        return tagDao.observeAll().map { entities -> entities.map { it.toDomain() } }
    }

    override fun observeDocumentTags(documentId: Long): Flow<List<Tag>> {
        return tagDao.observeDocumentTags(documentId).map { entities -> entities.map { it.toDomain() } }
    }

    override fun observeDocumentTagMap(): Flow<Map<Long, List<Tag>>> {
        return tagDao.observeAllDocumentTags().map { rows ->
            rows.groupBy { it.documentId }.mapValues { (_, tags) ->
                tags.map { Tag(id = it.tagId, name = it.tagName, colorIndex = it.tagColorIndex) }
            }
        }
    }

    override fun searchTags(query: String): Flow<List<Tag>> {
        return tagDao.search(query).map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun createTag(name: String): Long {
        val nextColor = (tagDao.getMaxColorIndex() + 1) % 8
        return tagDao.insert(TagEntity(name = name, colorIndex = nextColor))
    }

    override suspend fun renameTag(tagId: Long, name: String) {
        tagDao.update(TagEntity(id = tagId, name = name))
    }

    override suspend fun deleteTags(tagIds: List<Long>) {
        tagDao.deleteByIds(tagIds)
    }

    override suspend fun setDocumentTags(documentId: Long, tagIds: List<Long>) {
        tagDao.deleteAllDocumentTags(documentId)
        for (tagId in tagIds) {
            tagDao.insertDocumentTag(DocumentTagCrossRef(documentId = documentId, tagId = tagId))
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

    override fun observeTagAutomations(tagId: Long): Flow<List<TagAutomation>> {
        return tagAutomationDao.observeByTagId(tagId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getAutomationsForTagIds(tagIds: List<Long>): List<TagAutomation> {
        return tagAutomationDao.getByTagIds(tagIds).map { it.toDomain() }
    }

    override suspend fun createAutomation(automation: TagAutomation): Long {
        return tagAutomationDao.insert(automation.toEntity())
    }

    override suspend fun deleteAutomation(id: Long) {
        tagAutomationDao.deleteById(id)
    }

    private fun TagEntity.toDomain(): Tag {
        return Tag(
            id = id,
            name = name,
            colorIndex = colorIndex,
        )
    }

    private fun deleteDocumentFiles(doc: DocumentEntity) {
        doc.outputUri?.let { uriStr ->
            try {
                val uri = Uri.parse(uriStr)
                when (uri.scheme) {
                    "content" -> DocumentsContract.deleteDocument(app.contentResolver, uri)
                    "file" -> File(uri.path!!).delete()
                    else -> {}
                }
            } catch (_: Exception) { }
        }
        File(app.cacheDir, "pages/${doc.id}").deleteRecursively()
    }
}
