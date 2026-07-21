package com.picpocket.app.drive.sync

import com.picpocket.app.drive.DriveAuthManager
import com.picpocket.app.drive.EncryptionManager
import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveFileManager @Inject constructor(
    private val driveAuthManager: DriveAuthManager,
    private val encryptionManager: EncryptionManager,
) {
    private val service: Drive?
        get() = driveAuthManager.driveService

    suspend fun createFolder(name: String): String? {
        val drive = service ?: return null
        val metadata = File().setName(name).setMimeType(FOLDER_MIME)
        val folder = drive.files().create(metadata)
            .setFields("id")
            .execute()
        return folder.id
    }

    suspend fun findFolder(name: String): String? {
        val drive = service ?: return null
        val query = "name = '$name' and mimeType = '$FOLDER_MIME' and trashed = false"
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("appDataFolder")
            .setFields("files(id)")
            .execute()
        return result.files.firstOrNull()?.id
    }

    suspend fun createOrGetFolder(name: String): String? {
        return findFolder(name) ?: createFolder(name)
    }

    suspend fun uploadFile(parentFolderId: String, fileName: String, data: ByteArray, mimeType: String = "application/octet-stream"): String? {
        val drive = service ?: return null
        val encrypted = encryptionManager.encrypt(data)
        val finalName = encryptionManager.encryptFilename(fileName)
        val metadata = File()
            .setName(finalName)
            .setParents(listOf(parentFolderId))
        val content = ByteArrayContent(mimeType, encrypted)
        val file = drive.files().create(metadata, content)
            .setFields("id")
            .execute()
        return file.id
    }

    suspend fun downloadFile(fileId: String): ByteArray? {
        val drive = service ?: return null
        val stream = ByteArrayOutputStream()
        drive.files().get(fileId).executeMediaAndDownloadTo(stream)
        val encrypted = stream.toByteArray()
        return encryptionManager.decrypt(encrypted)
    }

    suspend fun findFilesInFolder(folderId: String): List<File> {
        val drive = service ?: return emptyList()
        val query = "'$folderId' in parents and trashed = false"
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("appDataFolder")
            .setFields("files(id, name, mimeType, size)")
            .execute()
        return result.files
    }

    suspend fun deleteFile(fileId: String) {
        val drive = service ?: return
        drive.files().delete(fileId).execute()
    }

    suspend fun listAllFolders(): List<File> {
        val drive = service ?: return emptyList()
        val query = "mimeType = '$FOLDER_MIME' and trashed = false"
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("appDataFolder")
            .setFields("files(id, name)")
            .execute()
        return result.files
    }

    companion object {
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
