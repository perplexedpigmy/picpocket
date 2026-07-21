package com.picpocket.app.drive.sync

import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DeviceRegistryTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val driveFileManager = mockk<DriveFileManager>()
    private val documentStore = mockk<DocumentStore>()
    private val localDriveIndex = mockk<LocalDriveIndex>()
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var registry: DeviceRegistry

    private val baseDoc = StoredDocument(
        id = "doc-1",
        name = "Test Document",
        createdAt = 100L,
        updatedAt = 200L,
    )

    @Before
    fun setUp() {
        every { localDriveIndex.getDevices() } returns emptyMap()
        registry = DeviceRegistry(driveFileManager, documentStore, localDriveIndex)
    }

    @Test
    fun `no orphans initially`() {
        assertTrue(registry.getOrphans().isEmpty())
    }

    @Test
    fun `detectOrphans finds deleted remote with local copy`() = runTest {
        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                folderId = "f-1",
                files = mapOf(".deleted" to "tombstone-id"),
                metadata = null,
                isDeleted = true,
            ),
        )
        coEvery { driveFileManager.downloadFile("tombstone-id") } returns
            """{"deletedAt":300,"byDevice":"device-1","acknowledgedBy":["device-1"]}""".toByteArray()
        every { localDriveIndex.getDevices() } returns mapOf("device-1" to DeviceInfo("Phone", 100L, 200L))

        registry.detectOrphans(listOf(baseDoc), remote)

        val orphans = registry.getOrphans()
        assertEquals(1, orphans.size)
        assertEquals("doc-1", orphans.first().docId)
        assertEquals("Phone", orphans.first().deletingDeviceName)
        assertEquals(300L, orphans.first().deletedAt)
    }

    @Test
    fun `detectOrphans ignores deleted remote when local doc not present`() = runTest {
        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                folderId = "f-1",
                files = mapOf(".deleted" to "tombstone-id"),
                metadata = null,
                isDeleted = true,
            ),
        )

        registry.detectOrphans(emptyList(), remote)
        assertTrue(registry.getOrphans().isEmpty())
    }

    @Test
    fun `detectOrphans ignores non-deleted remotes`() = runTest {
        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                folderId = "f-1",
                files = emptyMap(),
                metadata = baseDoc.copy(syncVersion = 1),
                isDeleted = false,
            ),
        )

        registry.detectOrphans(listOf(baseDoc), remote)
        assertTrue(registry.getOrphans().isEmpty())
    }

    @Test
    fun `keepOrphan marks document as dirty and removes tombstone`() = runTest {
        coEvery { localDriveIndex.getDocumentInfo("doc-1") } returns DocumentDriveInfo(folderId = "f-1")
        coEvery { driveFileManager.deleteFile("f-1/.deleted") } returns Unit
        coEvery { documentStore.readMetadata("doc-1") } returns Result.success(baseDoc)
        coEvery { documentStore.writeMetadata(any(), any()) } returns Result.success(Unit)

        registry.keepOrphan("doc-1")
    }

    @Test
    fun `deleteOrphanLocally removes document and acknowledges tombstone`() = runTest {
        every { localDriveIndex.getLocalDeviceId() } returns "local-device"
        coEvery { localDriveIndex.getDocumentInfo("doc-1") } returns DocumentDriveInfo(folderId = "f-1")
        coEvery { driveFileManager.findFilesInFolder("f-1") } returns emptyList()
        coEvery { documentStore.deleteDocument("doc-1") } returns Result.success(Unit)
        coEvery { documentStore.readMetadata("doc-1") } returns Result.success(baseDoc)

        registry.deleteOrphanLocally("doc-1")
    }

    @Test
    fun `dismissOrphan acknowledges tombstone without changing local`() = runTest {
        every { localDriveIndex.getLocalDeviceId() } returns "local-device"
        coEvery { localDriveIndex.getDocumentInfo("doc-1") } returns DocumentDriveInfo(folderId = "f-1")
        coEvery { driveFileManager.findFilesInFolder("f-1") } returns emptyList()

        registry.dismissOrphan("doc-1")
    }

    @Test
    fun `cleanDrive does not purge when devices exist`() = runTest {
        every { localDriveIndex.getAllKnownDeviceIds() } returns setOf("device-1", "device-2")
        every { localDriveIndex.getRootFolderId() } returns ""
        coEvery { driveFileManager.listAllFolders(any()) } returns emptyList()
        registry.cleanDrive()
    }

    @Test
    fun `cleanDrive does nothing with no known devices`() = runTest {
        every { localDriveIndex.getAllKnownDeviceIds() } returns emptySet()
        registry.cleanDrive()
    }
}
