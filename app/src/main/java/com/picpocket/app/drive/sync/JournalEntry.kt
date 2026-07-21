package com.picpocket.app.drive.sync

import kotlinx.serialization.Serializable

@Serializable
sealed interface JournalEntry {
    val docId: String

    @Serializable
    data class AddPage(
        override val docId: String,
        val pageNumber: Int,
        val filename: String,
        val fileSizeBytes: Long,
    ) : JournalEntry

    @Serializable
    data class RemovePage(
        override val docId: String,
        val pageNumber: Int,
    ) : JournalEntry

    @Serializable
    data class ReplacePageImage(
        override val docId: String,
        val pageNumber: Int,
        val fileSizeBytes: Long,
    ) : JournalEntry

    @Serializable
    data class ReorderPages(
        override val docId: String,
        val orderedPageNumbers: List<Int>,
    ) : JournalEntry

    @Serializable
    data class UpdateDocumentName(
        override val docId: String,
        val name: String,
    ) : JournalEntry

    @Serializable
    data class UpdatePageOcr(
        override val docId: String,
        val pageNumber: Int,
        val ocrText: String,
    ) : JournalEntry

    @Serializable
    data class ReplacePages(
        override val docId: String,
        val keptFilenames: List<String>,
    ) : JournalEntry

    @Serializable
    data class ReEncrypt(
        override val docId: String,
    ) : JournalEntry
}
