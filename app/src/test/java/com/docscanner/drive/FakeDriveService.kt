package com.docscanner.drive

class FakeDriveFile(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "",
    val mimeType: String = "application/octet-stream",
    var data: ByteArray = byteArrayOf(),
    var parents: List<String> = emptyList(),
    var trashed: Boolean = false,
)

class FakeDriveService {
    private val files = mutableMapOf<String, FakeDriveFile>()
    private var nextId = 1

    fun createFile(name: String, mimeType: String, data: ByteArray, parentId: String? = null): FakeDriveFile {
        val id = "fake-file-${nextId++}"
        val file = FakeDriveFile(
            id = id,
            name = name,
            mimeType = mimeType,
            data = data,
            parents = if (parentId != null) listOf(parentId) else emptyList(),
        )
        files[id] = file
        return file
    }

    fun getFile(id: String): FakeDriveFile? = files[id]

    fun deleteFile(id: String) {
        files[id]?.trashed = true
    }

    fun findFilesByParent(parentId: String): List<FakeDriveFile> {
        return files.values.filter { parentId in it.parents && !it.trashed }
    }

    fun findFolderByName(name: String): FakeDriveFile? {
        return files.values.find {
            it.name == name && it.mimeType == FOLDER_MIME && !it.trashed
        }
    }

    fun listAllFolders(): List<FakeDriveFile> {
        return files.values.filter { it.mimeType == FOLDER_MIME && !it.trashed }
    }

    fun createFolder(name: String, parentId: String? = null): FakeDriveFile {
        return createFile(name, FOLDER_MIME, byteArrayOf(), parentId)
    }

    fun writeTombstone(folderId: String, deviceId: String, acknowledgedBy: List<String> = listOf(deviceId)) {
        val json = "{\"deletedAt\":${System.currentTimeMillis()},\"byDevice\":\"$deviceId\",\"acknowledgedBy\":[${
            acknowledgedBy.joinToString(",") { "\"$it\"" }
        }]}"
        createFile(".deleted", "application/json", json.toByteArray(), folderId)
    }

    fun createDocumentFolder(docId: String): String {
        val folder = createFolder(docId)
        return folder.id
    }

    companion object {
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
