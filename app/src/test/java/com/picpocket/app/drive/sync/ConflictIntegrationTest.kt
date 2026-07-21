package com.picpocket.app.drive.sync

import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class ConflictIntegrationTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val documentStore = mockk<com.picpocket.app.data.store.DocumentStore>()
    private val downloadEngine = mockk<DownloadEngine>()
    private val uploadEngine = mockk<UploadEngine>()
    private val localDriveIndex = mockk<LocalDriveIndex>()

    private lateinit var conflictResolver: ConflictResolver

    @Before
    fun setUp() {
        conflictResolver = ConflictResolver(documentStore, downloadEngine, uploadEngine, localDriveIndex)
    }

    @Test
    fun `concurrent edit creates conflict`() = runTest {
        val doc1 = StoredDocument(
            id = "doc-1",
            name = "Shared Doc",
            createdAt = 0L,
            updatedAt = 100L,
            syncVersion = 1,
            syncTimestamp = 100L,
        )

        val doc2 = doc1.copy(syncVersion = 5, syncTimestamp = 500L)

        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                fileNames = emptyList(),
                metadata = doc2,
                isDeleted = false,
            ),
        )

        conflictResolver.detectConflicts(listOf(doc1), remote)

        val conflicts = conflictResolver.getActiveConflicts()
        assertEquals(1, conflicts.size)
        assertEquals("doc-1", conflicts.first().docId)
    }

    @Test
    fun `resolve conflict then verify no active conflicts`() = runTest {
        val doc1 = StoredDocument(
            id = "doc-1",
            name = "Shared Doc",
            createdAt = 0L,
            updatedAt = 100L,
            syncVersion = 5,
            syncTimestamp = 500L,
        )
        val doc2 = doc1.copy(syncVersion = 3, syncTimestamp = 300L)

        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                fileNames = emptyList(),
                metadata = doc2,
                isDeleted = false,
            ),
        )

        conflictResolver.detectConflicts(listOf(doc1), remote)

        coEvery { documentStore.writeMetadata(any(), any()) } returns Result.success(Unit)
        conflictResolver.resolveConflict("doc-1", keepLocal = false)

        assertTrue(conflictResolver.getActiveConflicts().isEmpty())
    }

    @Test
    fun `no conflict when versions match`() = runTest {
        val doc = StoredDocument(
            id = "doc-1",
            name = "Shared Doc",
            createdAt = 0L,
            updatedAt = 100L,
            syncVersion = 3,
            syncTimestamp = 300L,
        )

        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                fileNames = emptyList(),
                metadata = doc,
                isDeleted = false,
            ),
        )

        conflictResolver.detectConflicts(listOf(doc), remote)
        assertTrue(conflictResolver.getActiveConflicts().isEmpty())
    }
}
