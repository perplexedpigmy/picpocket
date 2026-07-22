package com.picpocket.app.drive.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val LOCK_FILE = "sync-lock.json"
private const val HEARTBEAT_INTERVAL_MS = 30_000L
private const val STALE_TIMEOUT_MS = 300_000L

@Serializable
data class LockData(
    val lockedBy: String,
    val claimToken: String,
    val acquiredAt: Long,
    val heartbeat: Long,
)

@Singleton
class SyncMutex @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localDriveIndex: LocalDriveIndex,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var treeUri: String = ""
    private var deviceId: String = ""
    private var claimToken: String = ""

    suspend fun initialize() {
        treeUri = localDriveIndex.getRootTreeUri()
        deviceId = localDriveIndex.getLocalDeviceId()
    }

    suspend fun acquire(): Boolean = withContext(Dispatchers.IO) {
        if (treeUri.isBlank()) return@withContext false
        if (deviceId.isBlank()) return@withContext false

        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext false
        val existing = root.findFile(LOCK_FILE)

        if (existing != null) {
            val data = readLockData(existing)
            if (data != null) {
                val age = System.currentTimeMillis() - data.heartbeat
                if (age < STALE_TIMEOUT_MS) {
                    return@withContext false
                }
            }
            existing.delete()
        }

        claimToken = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val lock = LockData(
            lockedBy = deviceId,
            claimToken = claimToken,
            acquiredAt = now,
            heartbeat = now,
        )
        val file = root.createFile("application/json", LOCK_FILE) ?: return@withContext false
        context.contentResolver.openOutputStream(file.uri)?.use {
            it.write(json.encodeToString(lock).toByteArray(Charsets.UTF_8))
        } ?: return@withContext false

        val verification = readLockData(root.findFile(LOCK_FILE))
        verification != null && verification.lockedBy == deviceId && verification.claimToken == claimToken
    }

    suspend fun heartbeat() {
        if (treeUri.isBlank() || deviceId.isBlank() || claimToken.isBlank()) return
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext
            val file = root.findFile(LOCK_FILE) ?: return@withContext
            val data = readLockData(file) ?: return@withContext
            if (data.lockedBy != deviceId || data.claimToken != claimToken) return@withContext
            val updated = data.copy(heartbeat = System.currentTimeMillis())
            context.contentResolver.openOutputStream(file.uri)?.use {
                it.write(json.encodeToString(updated).toByteArray(Charsets.UTF_8))
            }
        }
    }

    suspend fun release() {
        if (treeUri.isBlank()) return
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext
            val file = root.findFile(LOCK_FILE) ?: return@withContext
            val data = readLockData(file)
            if (data != null && data.lockedBy == deviceId && data.claimToken == claimToken) {
                file.delete()
            }
        }
    }

    private fun readLockData(file: DocumentFile?): LockData? {
        if (file == null || !file.exists()) return null
        val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: return null
        return try {
            json.decodeFromString(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}
