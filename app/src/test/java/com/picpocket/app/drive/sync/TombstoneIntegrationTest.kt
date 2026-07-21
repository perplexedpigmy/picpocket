package com.picpocket.app.drive.sync

import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.drive.DriveAuthManager
import com.picpocket.app.drive.sync.DriveFileManager
import com.picpocket.app.drive.EncryptionManager
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class TombstoneIntegrationTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val encryptionManager = EncryptionManager()
    private val driveAuthManager = mockk<DriveAuthManager>()
    private val documentStore = mockk<DocumentStore>()
    private val localDriveIndex = mockk<LocalDriveIndex>()
    private val driveFileManager = mockk<DriveFileManager>()
    private val downloadEngine = mockk<DownloadEngine>()

    private lateinit var uploadEngine: UploadEngine
    private lateinit var deviceRegistry: DeviceRegistry

    private val baseDoc = StoredDocument(
        id = "doc-1",
        name = "Test Document",
        createdAt = 100L,
        updatedAt = 200L,
    )

    @Before
    fun setUp() {
        encryptionManager.setPassphrase("tombstone-test")
        uploadEngine = UploadEngine(driveFileManager, documentStore, localDriveIndex, encryptionManager, mockk())
        deviceRegistry = DeviceRegistry(driveFileManager, documentStore, localDriveIndex)
    }

    private fun tombstoneJson(by: String, acknowledged: List<String>): String =
        """{"deletedAt":300,"byDevice":"$by","acknowledgedBy":${acknowledged.joinToString(",") { "\"$it\"" }}}"""

    @Test
    fun `create tombstone then detect orphan`() = runTest {
        coEvery { localDriveIndex.getDocumentInfo("doc-1") } returns DocumentDriveInfo(folderId = "f-1")
        coEvery { driveFileManager.uploadFile("f-1", ".deleted", any()) } returns "tombstone-id"

        uploadEngine.uploadDeletedTombstone("doc-1", "device-1")

        coEvery { driveFileManager.downloadFile("tombstone-id") } returns
            tombstoneJson("device-1", listOf("device-1")).toByteArray()
        every { localDriveIndex.getDevices() } returns mapOf("device-1" to DeviceInfo("Phone", 100L, 200L))

        val remote = listOf(
            DownloadEngine.RemoteDocument(
                docId = "doc-1",
                folderId = "f-1",
                files = mapOf(".deleted" to "tombstone-id"),
                metadata = null,
                isDeleted = true,
            ),
        )

        deviceRegistry.detectOrphans(listOf(baseDoc), remote)

        val orphans = deviceRegistry.getOrphans()
        assertEquals(1, orphans.size)
        assertEquals("doc-1", orphans.first().docId)
    }

    @Test
    fun `acknowledge tombstone then clean`() = runTest {
        every { localDriveIndex.getAllKnownDeviceIds() } returns setOf("device-1", "device-2")
        every { localDriveIndex.getLocalDeviceId() } returns "device-2"

        val folderFile = com.google.api.services.drive.model.File().setId("f-1").setName("doc-1")
        coEvery { driveFileManager.listAllFolders() } returns listOf(folderFile)

        val tombstoneFile = com.google.api.services.drive.model.File().setId("tombstone-id").setName(".deleted")
        coEvery { driveFileManager.findFilesInFolder("f-1") } returns listOf(tombstoneFile)

        coEvery { driveFileManager.downloadFile("tombstone-id") } returns
            tombstoneJson("device-1", listOf("device-1")).toByteArray()

        coEvery { driveFileManager.uploadFile("f-1", ".deleted", any()) } returns "updated-tombstone"

        coEvery { driveFileManager.downloadFile("tombstone-id") } returns
            tombstoneJson("device-1", listOf("device-1", "device-2")).toByteArray()

        coEvery { driveFileManager.deleteFile("tombstone-id") } returns Unit
        coEvery { driveFileManager.findFilesInFolder("f-1") } returns emptyList()
        coEvery { driveFileManager.deleteFile("f-1") } returns Unit

        deviceRegistry.cleanDrive()
    }
}
