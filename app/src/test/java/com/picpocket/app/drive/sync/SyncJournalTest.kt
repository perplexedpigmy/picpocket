package com.picpocket.app.drive.sync

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SyncJournalTest {

    private val checkpointStore = mockk<CheckpointStore>(relaxed = true)
    private lateinit var journal: SyncJournal

    private var cursor: Int = 0

    @Before
    fun setUp() {
        cursor = 0
        every { checkpointStore.cursor } answers { cursor }
        every { checkpointStore.cursor = any() } answers { cursor = invocation.args[0] as Int }
        every { checkpointStore.reset() } answers { cursor = 0 }
        journal = SyncJournal(RuntimeEnvironment.getApplication(), checkpointStore)
    }

    @Test
    fun `isEmpty returns true for empty journal`() {
        assertTrue(journal.isEmpty())
    }

    @Test
    fun `isEmpty returns false after entry appended`() {
        journal.append(JournalEntry.AddPage("doc-1", 1, "page_001.jpg", 1024L))
        assertFalse(journal.isEmpty())
    }

    @Test
    fun `entriesFromCheckpoint returns all entries when cursor at zero`() {
        journal.append(JournalEntry.AddPage("doc-1", 1, "page_001.jpg", 1024L))
        journal.append(JournalEntry.RemovePage("doc-1", 2))

        val entries = journal.entriesFromCheckpoint()
        assertEquals(2, entries.size)
        assertTrue(entries[0] is JournalEntry.AddPage)
        assertTrue(entries[1] is JournalEntry.RemovePage)
    }

    @Test
    fun `entriesFromCheckpoint returns only entries after cursor`() {
        journal.append(JournalEntry.AddPage("doc-1", 1, "page_001.jpg", 1024L))
        journal.append(JournalEntry.RemovePage("doc-1", 2))
        cursor = 1

        val entries = journal.entriesFromCheckpoint()
        assertEquals(1, entries.size)
        assertTrue(entries[0] is JournalEntry.RemovePage)
    }

    @Test
    fun `advanceCheckpoint increments cursor`() {
        journal.advanceCheckpoint()
        assertEquals(1, cursor)
    }

    @Test
    fun `truncate removes processed entries when all are processed`() {
        journal.append(JournalEntry.AddPage("doc-1", 1, "page_001.jpg", 1024L))
        cursor = 1

        journal.truncate()
        assertTrue(journal.isEmpty())
    }

    @Test
    fun `journal survives append across instances`() {
        journal.append(JournalEntry.AddPage("doc-1", 1, "page_001.jpg", 1024L))

        val journal2 = SyncJournal(RuntimeEnvironment.getApplication(), checkpointStore)
        assertFalse(journal2.isEmpty())
        val entries = journal2.entriesFromCheckpoint()
        assertEquals(1, entries.size)
    }

    @Test
    fun `entries preserve insertion order`() {
        journal.append(JournalEntry.AddPage("doc-1", 1, "page_001.jpg", 1024L))
        journal.append(JournalEntry.AddPage("doc-1", 2, "page_002.jpg", 2048L))
        journal.append(JournalEntry.RemovePage("doc-1", 1))

        val entries = journal.entriesFromCheckpoint()
        assertEquals(3, entries.size)
        val add1 = entries[0] as JournalEntry.AddPage
        assertEquals(1, add1.pageNumber)
        val add2 = entries[1] as JournalEntry.AddPage
        assertEquals(2, add2.pageNumber)
        assertTrue(entries[2] is JournalEntry.RemovePage)
    }

    @Test
    fun `truncate only removes up to cursor, not beyond`() {
        journal.append(JournalEntry.AddPage("doc-1", 1, "page_001.jpg", 1024L))
        journal.append(JournalEntry.RemovePage("doc-1", 2))
        cursor = 1

        journal.truncate()

        val entries = journal.entriesFromCheckpoint()
        assertEquals(1, entries.size)
        assertTrue(entries[0] is JournalEntry.RemovePage)
    }

    @Test
    fun `crashes and resumes from checkpoint`() {
        journal.append(JournalEntry.AddPage("doc-1", 1, "page_001.jpg", 1024L))
        journal.append(JournalEntry.RemovePage("doc-1", 2))
        journal.append(JournalEntry.AddPage("doc-1", 3, "page_003.jpg", 512L))

        cursor = 2

        val entries = journal.entriesFromCheckpoint()
        assertEquals(1, entries.size)
        assertEquals(3, (entries[0] as JournalEntry.AddPage).pageNumber)
    }
}
