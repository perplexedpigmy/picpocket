package com.docscanner.data.store

import android.app.Application
import com.docscanner.data.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class StoredDocument(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: MutableList<StoredPage> = mutableListOf(),
    val tags: List<String> = emptyList(),
    val qualityTier: Int = 0,
    val ocrComplete: Boolean = false,
    val pageSize: String? = null,
)

@Serializable
data class StoredPage(
    val pageNumber: Int,
    val filename: String,
    val fileSizeBytes: Long = 0,
    val filterTypeOrdinal: Int = 0,
    val ocrText: String? = null,
    val createdAt: Long,
)

@Singleton
class DocumentStore @Inject constructor(
    private val app: Application,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val documentsRoot: File
        get() = File(app.filesDir, "documents")

    fun documentDir(documentId: String): File =
        File(documentsRoot, documentId)

    fun metadataFile(documentId: String): File =
        File(documentDir(documentId), "metadata.json")

    fun pageFile(documentId: String, filename: String): File =
        File(documentDir(documentId), filename)

    suspend fun readMetadata(documentId: String): StoredDocument? = withContext(Dispatchers.IO) {
        val file = metadataFile(documentId)
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString<StoredDocument>(file.readText())
        } catch (_: Exception) { null }
    }

    suspend fun writeMetadata(documentId: String, doc: StoredDocument) = withContext(Dispatchers.IO) {
        val dir = documentDir(documentId)
        dir.mkdirs()
        val tmp = File(dir, "metadata.json.tmp")
        val dest = metadataFile(documentId)
        tmp.writeText(json.encodeToString(doc))
        tmp.renameTo(dest)
    }

    suspend fun createDocument(
        name: String,
        qualityTier: Int = 0,
        pageSize: String? = null,
    ): StoredDocument = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val doc = StoredDocument(
            id = id,
            name = name,
            createdAt = now,
            updatedAt = now,
            qualityTier = qualityTier,
            pageSize = pageSize,
        )
        writeMetadata(id, doc)
        doc
    }

    suspend fun listDocuments(): List<StoredDocument> = withContext(Dispatchers.IO) {
        val root = documentsRoot
        if (!root.exists()) return@withContext emptyList()
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> readMetadata(dir.name) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    suspend fun deleteDocument(documentId: String) = withContext(Dispatchers.IO) {
        documentDir(documentId).deleteRecursively()
    }

    suspend fun addPage(
        documentId: String,
        pageNumber: Int,
        filename: String,
        fileSizeBytes: Long,
        filterTypeOrdinal: Int = 0,
        createdAt: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId) ?: return@withContext
        doc.pages.add(
            StoredPage(
                pageNumber = pageNumber,
                filename = filename,
                fileSizeBytes = fileSizeBytes,
                filterTypeOrdinal = filterTypeOrdinal,
                createdAt = createdAt,
            )
        )
        writeMetadata(documentId, doc.copy(updatedAt = System.currentTimeMillis(), pages = doc.pages))
    }

    suspend fun removePage(documentId: String, pageNumber: Int) = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId) ?: return@withContext
        doc.pages.removeAll { it.pageNumber == pageNumber }
        renumberPages(doc)
        writeMetadata(documentId, doc.copy(updatedAt = System.currentTimeMillis()))
        val pageFile = pageFile(documentId, filenameForPage(pageNumber))
        pageFile.delete()
    }

    suspend fun reorderPages(documentId: String, orderedPageNumbers: List<Int>) = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId) ?: return@withContext
        val pageMap = doc.pages.associateBy { it.pageNumber }
        val reordered = orderedPageNumbers.mapNotNull { pageMap[it] }
        val updated = reordered.mapIndexed { index, page ->
            page.copy(pageNumber = index + 1)
        }
        writeMetadata(documentId, doc.copy(pages = updated.toMutableList(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun updatePageOcrText(documentId: String, pageNumber: Int, ocrText: String) = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId) ?: return@withContext
        val idx = doc.pages.indexOfFirst { it.pageNumber == pageNumber }
        if (idx < 0) return@withContext
        doc.pages[idx] = doc.pages[idx].copy(ocrText = ocrText)
        writeMetadata(documentId, doc.copy(pages = doc.pages))
    }

    suspend fun updateDocumentName(documentId: String, name: String) = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId) ?: return@withContext
        writeMetadata(documentId, doc.copy(name = name, updatedAt = System.currentTimeMillis()))
    }

    suspend fun nextPageNumber(documentId: String): Int = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId)
        (doc?.pages?.maxOfOrNull { it.pageNumber } ?: 0) + 1
    }

    suspend fun totalFileSize(documentId: String): Long = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId) ?: return@withContext 0L
        doc.pages.sumOf { it.fileSizeBytes }
    }

    fun filenameForPage(pageNumber: Int): String =
        "%05d".format(pageNumber)

    suspend fun replacePages(documentId: String, keptFilenames: List<String>): List<String> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId) ?: return@withContext emptyList()
        val kept = keptFilenames.mapNotNull { filename ->
            doc.pages.find { it.filename == filename }
        }
        val keptSet = keptFilenames.toSet()
        val removed = doc.pages.filter { it.filename !in keptSet }

        if (kept.isEmpty()) {
            deleteDocument(documentId)
            return@withContext removed.map { it.filename }
        }

        val updated = kept.mapIndexed { index, page ->
            page.copy(pageNumber = index + 1)
        }
        writeMetadata(documentId, doc.copy(pages = updated.toMutableList(), updatedAt = System.currentTimeMillis()))
        removed.map { it.filename }
    }

    private fun renumberPages(doc: StoredDocument) {
        doc.pages.sortBy { it.pageNumber }
        for ((i, page) in doc.pages.withIndex()) {
            doc.pages[i] = page.copy(pageNumber = i + 1)
        }
    }
}
