package com.picpocket.app.drive.sync

import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DeviceRegistryTombstoneTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val driveFileManager = mockk<DriveFileManager>()
    private val documentStore = mockk<DocumentStore>()
    private val localDriveIndex = mockk<LocalDriveIndex>()

    private lateinit var registry: DeviceRegistry

    @Before
    fun setUp() {
        registry = DeviceRegistry(driveFileManager, documentStore, localDriveIndex)
    }

    private fun tombstoneJson(by: String, acknowledged: List<String>): String =
        """{"deletedAt":300,"byDevice":"$by","acknowledgedBy":${acknowledged.joinToString(",") { "\"$it\"" }}}"""

    @Test
    fun `tombstone acknowledged by all devices triggers clean`() = runTest {
        every { localDriveIndex.getAllKnownDeviceIds() } returns setOf("device-1", "device-2")
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"

        val folderFile = com.google.api.services.drive.model.File().setId("f-1").setName("doc-1")
        coEvery { driveFileManager.listAllFolders() } returns listOf(folderFile)

        val tombstoneFile = com.google.api.services.drive.model.File().setId("tombstone-id").setName(".deleted")
        coEvery { driveFileManager.findFilesInFolder("f-1") } returns listOf(tombstoneFile)

        coEvery { driveFileManager.downloadFile("tombstone-id") } returns
            tombstoneJson("device-1", listOf("device-1", "device-2")).toByteArray()
        coEvery { driveFileManager.deleteFile("tombstone-id") } returns Unit
        coEvery { driveFileManager.findFilesInFolder("f-1") } returns emptyList()
        coEvery { driveFileManager.deleteFile("f-1") } returns Unit

        registry.cleanDrive()
    }

    @Test
    fun `tombstone not fully acknowledged is not cleaned`() = runTest {
        every { localDriveIndex.getAllKnownDeviceIds() } returns setOf("device-1", "device-2")

        val folderFile = com.google.api.services.drive.model.File().setId("f-1").setName("doc-1")
        coEvery { driveFileManager.listAllFolders() } returns listOf(folderFile)

        val tombstoneFile = com.google.api.services.drive.model.File().setId("tombstone-id").setName(".deleted")
        coEvery { driveFileManager.findFilesInFolder("f-1") } returns listOf(tombstoneFile)

        coEvery { driveFileManager.downloadFile("tombstone-id") } returns
            tombstoneJson("device-1", listOf("device-1")).toByteArray()

        registry.cleanDrive()

        coVerify(exactly = 0) { driveFileManager.deleteFile("tombstone-id") }
    }

    @Test
    fun `uploadDeletedTombstone writes tombstone data`() = runTest {
        coEvery { localDriveIndex.getDocumentInfo("doc-1") } returns DocumentDriveInfo(folderId = "f-1")
        coEvery { driveFileManager.uploadFile("f-1", ".deleted", any()) } returns "new-tombstone-id"

        val engine = UploadEngine(
            driveFileManager,
            documentStore,
            localDriveIndex,
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        engine.uploadDeletedTombstone("doc-1", "device-1")

        coVerify { driveFileManager.uploadFile("f-1", ".deleted", any()) }
    }
}
