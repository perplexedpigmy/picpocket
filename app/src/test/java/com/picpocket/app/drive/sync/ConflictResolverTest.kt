package com.picpocket.app.drive.sync

import com.picpocket.app.data.store.DocumentStore
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
class ConflictResolverTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val documentStore = mockk<DocumentStore>()
    private val downloadEngine = mockk<DownloadEngine>()
    private val uploadEngine = mockk<UploadEngine>()
    private val localDriveIndex = mockk<LocalDriveIndex>()

    private lateinit var resolver: ConflictResolver

    private val baseDoc = StoredDocument(
        id = "doc-1",
        name = "Test",
        createdAt = 100L,
        updatedAt = 200L,
    )

    @Before
    fun setUp() {
        resolver = ConflictResolver(documentStore, downloadEngine, uploadEngine, localDriveIndex)
    }

    @Test
    fun `no conflicts when local and remote match`() = runTest {
        val local = listOf(baseDoc.copy(syncVersion = 1, syncTimestamp = 100L))
        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                folderId = "f-1",
                files = emptyMap(),
                metadata = baseDoc.copy(syncVersion = 1, syncTimestamp = 100L),
                isDeleted = false,
            ),
        )

        resolver.detectConflicts(local, remote)
        assertTrue(resolver.getActiveConflicts().isEmpty())
    }

    @Test
    fun `no conflict when versions differ by one`() = runTest {
        val local = listOf(baseDoc.copy(syncVersion = 2, syncTimestamp = 150L))
        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                folderId = "f-1",
                files = emptyMap(),
                metadata = baseDoc.copy(syncVersion = 3, syncTimestamp = 200L),
                isDeleted = false,
            ),
        )

        resolver.detectConflicts(local, remote)
        assertTrue(resolver.getActiveConflicts().isEmpty())
    }

    @Test
    fun `conflict detected when divergent versions`() = runTest {
        val local = listOf(baseDoc.copy(syncVersion = 5, syncTimestamp = 200L))
        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                folderId = "f-1",
                files = emptyMap(),
                metadata = baseDoc.copy(syncVersion = 3, syncTimestamp = 150L),
                isDeleted = false,
            ),
        )

        resolver.detectConflicts(local, remote)
        assertEquals(1, resolver.getActiveConflicts().size)
    }

    @Test
    fun `no conflict for deleted remote documents`() = runTest {
        val local = listOf(baseDoc.copy(syncVersion = 5, syncTimestamp = 200L))
        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                folderId = "f-1",
                files = emptyMap(),
                metadata = null,
                isDeleted = true,
            ),
        )

        resolver.detectConflicts(local, remote)
        assertTrue(resolver.getActiveConflicts().isEmpty())
    }

    @Test
    fun `no conflict for remote without metadata`() = runTest {
        val local = listOf(baseDoc.copy(syncVersion = 5, syncTimestamp = 200L))
        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                folderId = "f-1",
                files = emptyMap(),
                metadata = null,
                isDeleted = false,
            ),
        )

        resolver.detectConflicts(local, remote)
        assertTrue(resolver.getActiveConflicts().isEmpty())
    }

    @Test
    fun `resolve conflict with keep local`() = runTest {
        val local = listOf(baseDoc.copy(syncVersion = 5, syncTimestamp = 200L))
        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                folderId = "f-1",
                files = emptyMap(),
                metadata = baseDoc.copy(syncVersion = 3, syncTimestamp = 150L),
                isDeleted = false,
            ),
        )

        resolver.detectConflicts(local, remote)
        val conflict = resolver.getActiveConflicts().first()
        coEvery { uploadEngine.uploadDocument(any()) } returns true

        val result = resolver.resolveConflict(conflict.docId, keepLocal = true)
        assertTrue(result is ConflictResolution.KeepLocal)
    }

    @Test
    fun `resolve conflict with keep remote`() = runTest {
        val local = listOf(baseDoc.copy(syncVersion = 5, syncTimestamp = 200L))
        val remoteMeta = baseDoc.copy(syncVersion = 3, syncTimestamp = 150L)
        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                folderId = "f-1",
                files = emptyMap(),
                metadata = remoteMeta,
                isDeleted = false,
            ),
        )

        resolver.detectConflicts(local, remote)
        val conflict = resolver.getActiveConflicts().first()
        coEvery { documentStore.writeMetadata(any(), any()) } returns Result.success(Unit)

        val result = resolver.resolveConflict(conflict.docId, keepLocal = false)
        assertTrue(result is ConflictResolution.KeepRemote)
    }

    @Test
    fun `dismiss conflict removes it from active list`() = runTest {
        val local = listOf(baseDoc.copy(syncVersion = 5, syncTimestamp = 200L))
        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                folderId = "f-1",
                files = emptyMap(),
                metadata = baseDoc.copy(syncVersion = 3, syncTimestamp = 150L),
                isDeleted = false,
            ),
        )

        resolver.detectConflicts(local, remote)
        resolver.dismissConflict("doc-1")
        assertTrue(resolver.getActiveConflicts().isEmpty())
    }
}
