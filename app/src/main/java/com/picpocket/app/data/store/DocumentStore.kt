package com.picpocket.app.data.store

import android.app.Application
import com.picpocket.app.drive.sync.JournalEntry
import com.picpocket.app.drive.sync.SyncJournal
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
    val syncVersion: Int = 0,
    val syncTimestamp: Long = 0L,
    val syncExclude: Boolean = false,
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
    private val syncJournal: SyncJournal,
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

    suspend fun readMetadata(documentId: String): Result<StoredDocument> = withContext(Dispatchers.IO) {
        val file = metadataFile(documentId)
        if (!file.exists()) return@withContext Result.failure(Exception("Document not found: $documentId"))
        try {
            Result.success(json.decodeFromString<StoredDocument>(file.readText()))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun writeMetadata(documentId: String, doc: StoredDocument): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val dir = documentDir(documentId)
            dir.mkdirs()
            val tmp = File(dir, "metadata.json.tmp")
            val dest = metadataFile(documentId)
            tmp.writeText(json.encodeToString(doc))
            tmp.renameTo(dest)
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun createDocument(
        name: String,
        qualityTier: Int = 0,
        pageSize: String? = null,
    ): Result<StoredDocument> = withContext(Dispatchers.IO) {
        try {
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
            writeMetadata(id, doc).getOrElse { return@withContext Result.failure(it) }
            Result.success(doc)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun listDocuments(): Result<List<StoredDocument>> = withContext(Dispatchers.IO) {
        try {
            val root = documentsRoot
            if (!root.exists()) return@withContext Result.success(emptyList())
            val docs = root.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { dir -> readMetadata(dir.name).getOrNull() }
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()
            Result.success(docs)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deleteDocument(documentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            documentDir(documentId).deleteRecursively()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun addPage(
        documentId: String,
        pageNumber: Int,
        filename: String,
        fileSizeBytes: Long,
        filterTypeOrdinal: Int = 0,
        createdAt: Long = System.currentTimeMillis(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        doc.pages.add(
            StoredPage(
                pageNumber = pageNumber,
                filename = filename,
                fileSizeBytes = fileSizeBytes,
                filterTypeOrdinal = filterTypeOrdinal,
                createdAt = createdAt,
            )
        )
        val newDoc = doc.copy(updatedAt = System.currentTimeMillis(), pages = doc.pages)
        writeMetadata(documentId, newDoc)
        syncJournal.append(JournalEntry.AddPage(documentId, pageNumber, filename, fileSizeBytes))
        Result.success(Unit)
    }

    suspend fun removePage(documentId: String, pageNumber: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        doc.pages.removeAll { it.pageNumber == pageNumber }
        renumberPages(doc)
        writeMetadata(documentId, doc.copy(updatedAt = System.currentTimeMillis()))
        val pageFile = pageFile(documentId, filenameForPage(pageNumber))
        pageFile.delete()
        syncJournal.append(JournalEntry.RemovePage(documentId, pageNumber))
        Result.success(Unit)
    }

    suspend fun reorderPages(documentId: String, orderedPageNumbers: List<Int>): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        val pageMap = doc.pages.associateBy { it.pageNumber }
        val reordered = orderedPageNumbers.mapNotNull { pageMap[it] }
        val updated = reordered.mapIndexed { index, page ->
            page.copy(pageNumber = index + 1)
        }
        writeMetadata(documentId, doc.copy(pages = updated.toMutableList(), updatedAt = System.currentTimeMillis()))
        syncJournal.append(JournalEntry.ReorderPages(documentId, orderedPageNumbers))
        Result.success(Unit)
    }

    suspend fun updatePageOcrText(documentId: String, pageNumber: Int, ocrText: String): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        val idx = doc.pages.indexOfFirst { it.pageNumber == pageNumber }
        if (idx < 0) return@withContext Result.failure(Exception("Page $pageNumber not found"))
        doc.pages[idx] = doc.pages[idx].copy(ocrText = ocrText)
        writeMetadata(documentId, doc.copy(pages = doc.pages))
        syncJournal.append(JournalEntry.UpdatePageOcr(documentId, pageNumber, ocrText))
        Result.success(Unit)
    }

    suspend fun updateDocumentName(documentId: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        writeMetadata(documentId, doc.copy(name = name, updatedAt = System.currentTimeMillis()))
        syncJournal.append(JournalEntry.UpdateDocumentName(documentId, name))
        Result.success(Unit)
    }

    suspend fun nextPageNumber(documentId: String): Result<Int> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        Result.success((doc.pages.maxOfOrNull { it.pageNumber } ?: 0) + 1)
    }

    suspend fun replacePageImage(
        documentId: String,
        pageNumber: Int,
        fileSizeBytes: Long,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        val idx = doc.pages.indexOfFirst { it.pageNumber == pageNumber }
        if (idx < 0) return@withContext Result.failure(Exception("Page $pageNumber not found"))
        doc.pages[idx] = doc.pages[idx].copy(
            fileSizeBytes = fileSizeBytes,
            ocrText = null,
        )
        writeMetadata(documentId, doc.copy(
            pages = doc.pages,
            ocrComplete = false,
            updatedAt = System.currentTimeMillis(),
        ))
        syncJournal.append(JournalEntry.ReplacePageImage(documentId, pageNumber, fileSizeBytes))
        Result.success(Unit)
    }

    suspend fun totalFileSize(documentId: String): Result<Long> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        Result.success(doc.pages.sumOf { it.fileSizeBytes })
    }

    fun filenameForPage(pageNumber: Int): String =
        "%05d".format(pageNumber)

    suspend fun replacePages(documentId: String, keptFilenames: List<String>): Result<List<String>> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        val kept = keptFilenames.mapNotNull { filename ->
            doc.pages.find { it.filename == filename }
        }
        val keptSet = keptFilenames.toSet()
        val removed = doc.pages.filter { it.filename !in keptSet }

        if (kept.isEmpty()) {
            deleteDocument(documentId)
            return@withContext Result.success(removed.map { it.filename })
        }

        val updated = kept.mapIndexed { index, page ->
            page.copy(pageNumber = index + 1)
        }
        val writeResult = writeMetadata(documentId, doc.copy(pages = updated.toMutableList(), updatedAt = System.currentTimeMillis()))
        writeResult.getOrElse { return@withContext Result.failure(it) }
        syncJournal.append(JournalEntry.ReplacePages(documentId, keptFilenames))
        Result.success(removed.map { it.filename })
    }

    suspend fun updateSyncExclude(documentId: String, excluded: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        writeMetadata(documentId, doc.copy(syncExclude = excluded))
    }

    private fun renumberPages(doc: StoredDocument) {
        doc.pages.sortBy { it.pageNumber }
        for ((i, page) in doc.pages.withIndex()) {
            doc.pages[i] = page.copy(pageNumber = i + 1)
        }
    }
}
