package com.docscanner.data.repository

import com.docscanner.data.local.dao.TagAutomationDao
import com.docscanner.data.local.dao.TagDao
import com.docscanner.data.local.entity.TagAutomationEntity
import com.docscanner.data.local.entity.TagEntity
import com.docscanner.data.local.entity.toDomain
import com.docscanner.data.local.entity.toEntity
import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.docscanner.data.model.Document
import com.docscanner.data.model.DocumentId
import com.docscanner.data.model.Page
import com.docscanner.data.model.Tag
import com.docscanner.data.model.TagAutomation
import com.docscanner.data.store.DocumentStore
import com.docscanner.data.store.StoredDocument
import com.docscanner.domain.pdfimport.PdfPageImporter
import com.docscanner.domain.ocr.OcrManager
import com.docscanner.domain.scan.PageEncoder
import com.docscanner.domain.scan.QualityTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val store: DocumentStore,
    private val tagDao: TagDao,
    private val tagAutomationDao: TagAutomationDao,
    private val pdfPageImporter: PdfPageImporter,
    private val ocrManager: OcrManager,
    private val app: Application,
) : DocumentRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _documents = MutableStateFlow<List<Document>>(emptyList())
    private val _tagChangeNotifier = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    init {
        scope.launch { refreshDocuments() }
    }

    private suspend fun refreshDocuments() {
        store.listDocuments().onSuccess { stored ->
            _documents.value = stored.map { it.toDomain() }
        }
    }

    override fun observeDocuments(): Flow<List<Document>> {
        scope.launch { refreshDocuments() }
        return _documents.asStateFlow()
    }

    override fun observeDocument(documentId: DocumentId): Flow<Document?> {
        return _documents.asStateFlow().map { list -> list.find { it.id == documentId } }
    }

    override fun observePages(documentId: DocumentId): Flow<List<Page>> {
        return _documents.asStateFlow().map { _ ->
            val stored = store.readMetadata(documentId).getOrNull() ?: return@map emptyList()
            stored.pages.mapIndexed { _, sp ->
                Page(
                    id = sp.pageNumber.toLong(),
                    documentId = documentId,
                    pageNumber = sp.pageNumber,
                    filename = sp.filename,
                    imageUri = store.pageFile(documentId, sp.filename).toURI().toString(),
                    ocrText = sp.ocrText,
                    filterTypeOrdinal = sp.filterTypeOrdinal,
                    createdAt = sp.createdAt,
                )
            }
        }
    }

    override suspend fun getDocument(documentId: DocumentId): Result<Document> {
        return store.readMetadata(documentId).map { it.toDomain() }
    }

    override suspend fun getPages(documentId: DocumentId): Result<List<Page>> {
        return store.readMetadata(documentId).map { stored ->
            stored.pages.map { sp ->
                Page(
                    id = sp.pageNumber.toLong(),
                    documentId = documentId,
                    pageNumber = sp.pageNumber,
                    filename = sp.filename,
                    imageUri = store.pageFile(documentId, sp.filename).toURI().toString(),
                    ocrText = sp.ocrText,
                    filterTypeOrdinal = sp.filterTypeOrdinal,
                    createdAt = sp.createdAt,
                )
            }
        }
    }

    override suspend fun createDocument(name: String, qualityTier: Int, pageSize: String?): Result<DocumentId> {
        return store.createDocument(name = name, qualityTier = qualityTier, pageSize = pageSize).map { stored ->
            scope.launch { refreshDocuments() }
            stored.id
        }
    }

    override suspend fun addPage(
        documentId: DocumentId,
        imageUri: String,
        filterTypeOrdinal: Int,
        fileSizeBytes: Long,
        qualityTier: Int,
    ): Result<Unit> {
        return store.nextPageNumber(documentId).mapCatching { pageNumber ->
            val filename = store.filenameForPage(pageNumber)
            val pageFile = store.pageFile(documentId, filename)
            val src = java.io.File(java.net.URI(imageUri))
            val tier = QualityTier.entries.getOrNull(qualityTier) ?: QualityTier.BEST
            PageEncoder.encodePage(src, pageFile, tier)
            store.addPage(
                documentId = documentId,
                pageNumber = pageNumber,
                filename = filename,
                fileSizeBytes = pageFile.length(),
                filterTypeOrdinal = filterTypeOrdinal,
            ).getOrThrow()
            scope.launch { refreshDocuments() }
        }
    }

    override suspend fun updatePageOcrText(documentId: DocumentId, pageNumber: Int, ocrText: String): Result<Unit> {
        return store.updatePageOcrText(documentId, pageNumber, ocrText)
    }

    override suspend fun updateDocumentName(documentId: DocumentId, name: String): Result<Unit> {
        return store.updateDocumentName(documentId, name).onSuccess {
            scope.launch { refreshDocuments() }
        }
    }

    override suspend fun getDocumentsByName(name: String): Result<List<Document>> {
        return store.listDocuments().map { stored ->
            stored.filter { it.name.equals(name, ignoreCase = true) }
                .map { it.toDomain() }
        }
    }

    override suspend fun getAllDocuments(): Result<List<Document>> {
        return store.listDocuments().map { stored -> stored.map { it.toDomain() } }
    }

    override suspend fun deleteDocumentsByName(name: String): Result<Unit> {
        return store.listDocuments().mapCatching { stored ->
            val docs = stored.filter { it.name.equals(name, ignoreCase = true) }
            for (doc in docs) store.deleteDocument(doc.id).getOrThrow()
            scope.launch { refreshDocuments() }
        }
    }

    override suspend fun deleteDocuments(documentIds: List<DocumentId>): Result<Unit> {
        return runCatching {
            for (id in documentIds) store.deleteDocument(id).getOrThrow()
            scope.launch { refreshDocuments() }
        }
    }

    override suspend fun deleteDocument(documentId: DocumentId): Result<Unit> {
        return store.deleteDocument(documentId).onSuccess {
            scope.launch { refreshDocuments() }
        }
    }

    override suspend fun deletePage(documentId: DocumentId, pageNumber: Int): Result<Unit> {
        return store.removePage(documentId, pageNumber).onSuccess {
            scope.launch { refreshDocuments() }
        }
    }

    override suspend fun replacePages(documentId: DocumentId, keptFilenames: List<String>): Result<Unit> {
        return store.replacePages(documentId, keptFilenames).mapCatching { orphaned ->
            for (filename in orphaned) {
                val file = store.pageFile(documentId, filename)
                if (file.exists()) file.delete()
            }
            scope.launch { refreshDocuments() }
        }
    }

    override suspend fun reorderPages(documentId: DocumentId, pageNumbers: List<Int>): Result<Unit> {
        return store.reorderPages(documentId, pageNumbers).onSuccess {
            scope.launch { refreshDocuments() }
        }
    }

    override suspend fun importPdf(uri: Uri): Result<DocumentId> {
        return runCatching {
            val contentResolver = app.contentResolver
            val displayName = resolvePdfDisplayName(contentResolver, uri)
            val doc = store.createDocument(name = displayName).getOrThrow()
            val targetDir = store.documentDir(doc.id)
            val tier = readDefaultQualityTier()
            val results = pdfPageImporter.import(contentResolver, uri, targetDir, tier).getOrThrow()

            if (results.isEmpty()) {
                store.deleteDocument(doc.id)
                throw Exception("Selected PDF has no pages")
            }

            for (r in results) {
                store.addPage(
                    documentId = doc.id,
                    pageNumber = r.pageNumber,
                    filename = r.filename,
                    fileSizeBytes = r.fileSizeBytes,
                ).getOrThrow()
            }

            scope.launch { refreshDocuments() }
            doc.id
        }
    }

    override suspend fun rescanPage(documentId: DocumentId, pageNumber: Int, imageUri: String): Result<Unit> {
        return store.readMetadata(documentId).mapCatching { doc ->
            val idx = doc.pages.indexOfFirst { it.pageNumber == pageNumber }
            if (idx < 0) throw Exception("Page $pageNumber not found in document $documentId")
            val filename = doc.pages[idx].filename
            val pageFile = store.pageFile(documentId, filename)

            val src = File(java.net.URI(imageUri))
            val tier = QualityTier.entries.getOrNull(doc.qualityTier) ?: QualityTier.BEST
            PageEncoder.encodePage(src, pageFile, tier)

            store.replacePageImage(
                documentId = documentId,
                pageNumber = pageNumber,
                fileSizeBytes = pageFile.length(),
            ).getOrThrow()

            scope.launch { refreshDocuments() }
            scope.launch { ocrManager.runOcr(documentId) }
        }
    }

    private fun resolvePdfDisplayName(contentResolver: android.content.ContentResolver, uri: Uri): String {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    val name = it.getString(nameIndex)
                    if (name != null) {
                        return name.substringBeforeLast(".")
                    }
                }
            }
        }
        val path = uri.lastPathSegment ?: "Imported Document"
        return path.substringBeforeLast(".")
    }

    private fun readDefaultQualityTier(): QualityTier {
        val prefs = app.getSharedPreferences("settings", 0)
        val ordinal = prefs.getInt("quality_tier", 0)
        return QualityTier.entries.getOrNull(ordinal) ?: QualityTier.BEST
    }

    override suspend fun searchDocumentsByOcrText(query: String): Result<Set<DocumentId>> {
        val regex = try { Regex(query, RegexOption.IGNORE_CASE) } catch (e: Exception) { return Result.success(emptySet()) }
        return store.listDocuments().map { docs ->
            docs.filter { doc ->
                doc.pages.any { page -> page.ocrText?.let { regex.containsMatchIn(it) } == true }
            }.map { it.id }.toSet()
        }
    }

    override fun observeAllTags(): Flow<List<Tag>> {
        return tagDao.observeAll().map { entities -> entities.map { it.toDomain() } }
    }

    override fun observeDocumentTags(documentId: DocumentId): Flow<List<Tag>> {
        return merge(_documents.asStateFlow(), _tagChangeNotifier.asSharedFlow()).map {
            val stored = store.readMetadata(documentId).getOrNull() ?: return@map emptyList()
            val tagNames = stored.tags
            if (tagNames.isEmpty()) return@map emptyList()
            val allTags = tagDao.getAll()
            tagNames.mapNotNull { name ->
                allTags.find { it.name.equals(name, ignoreCase = true) }?.toDomain()
            }
        }
    }

    override fun observeDocumentTagMap(): Flow<Map<DocumentId, List<Tag>>> {
        return _documents.asStateFlow().map { docs ->
            val allTagEntities = tagDao.getAll()
            val allTags = docs.mapNotNull { doc ->
                val stored = store.readMetadata(doc.id).getOrNull() ?: return@mapNotNull null
                val tagNames = stored.tags
                if (tagNames.isEmpty()) return@mapNotNull null
                doc.id to tagNames.mapNotNull { name ->
                    allTagEntities.find { it.name.equals(name, ignoreCase = true) }?.toDomain()
                }
            }
            allTags.toMap()
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

    override suspend fun setDocumentTags(documentId: DocumentId, tagIds: List<Long>) {
        val tags = tagDao.getByIds(tagIds)
        val tagNames = tags.map { it.name }
        val doc = store.readMetadata(documentId).getOrNull() ?: return
        store.writeMetadata(documentId, doc.copy(tags = tagNames))
        _tagChangeNotifier.emit(Unit)
        scope.launch { refreshDocuments() }
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

    private fun StoredDocument.toDomain(): Document {
        return Document(
            id = id,
            name = name,
            createdAt = createdAt,
            updatedAt = updatedAt,
            pageCount = pages.size,
            totalFileSize = pages.sumOf { it.fileSizeBytes },
            qualityTier = qualityTier,
            ocrComplete = ocrComplete,
            pageSize = pageSize,
        )
    }

    private fun TagEntity.toDomain(): Tag {
        return Tag(
            id = id,
            name = name,
            colorIndex = colorIndex,
        )
    }
}
