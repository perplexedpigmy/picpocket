package com.picpocket.app.drive.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncJournal @Inject constructor(
    @ApplicationContext context: Context,
    private val checkpointStore: CheckpointStore,
) {
    private val journalFile = File(context.filesDir, "sync_journal.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun append(entry: JournalEntry) {
        val entries = readAll()
        entries.add(entry)
        writeAll(entries)
    }

    fun entriesFromCheckpoint(): List<JournalEntry> {
        val all = readAll()
        val cursor = checkpointStore.cursor
        if (cursor >= all.size) return emptyList()
        return all.subList(cursor, all.size)
    }

    fun advanceCheckpoint() {
        checkpointStore.cursor = checkpointStore.cursor + 1
    }

    fun truncate() {
        val remaining = entriesFromCheckpoint()
        writeAll(remaining)
        checkpointStore.reset()
    }

    fun isEmpty(): Boolean {
        val all = readAll()
        return all.isEmpty() || checkpointStore.cursor >= all.size
    }

    private fun readAll(): MutableList<JournalEntry> {
        if (!journalFile.exists()) return mutableListOf()
        return try {
            val content = journalFile.readText()
            if (content.isBlank()) mutableListOf()
            else json.decodeFromString<List<JournalEntry>>(content).toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun writeAll(entries: List<JournalEntry>) {
        journalFile.parentFile?.mkdirs()
        val tmp = File(journalFile.parentFile, "sync_journal.json.tmp")
        tmp.writeText(json.encodeToString(entries))
        tmp.renameTo(journalFile)
    }
}
