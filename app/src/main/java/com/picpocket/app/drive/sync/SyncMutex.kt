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

// The lock is a DIRECTORY, not a file: file creation over the laggy SAF bridge
// is last-writer-wins (both stale devices can "create" the same name and the
// second write silently replaces the first), so a file lock can never prove
// exclusivity. Directory creation is a WebDAV MKCOL, which the SERVER resolves
// atomically: the second concurrent create for the same name fails (405), and
// the Nextcloud client surfaces that failure as a null createDirectory — even
// when its own cached listing was stale and never showed the existing
// directory. A non-null createDirectory therefore proves we are the sole
// creator, and the lock stays ours until we delete it. No re-checking needed.
private const val LOCK_DIR = ".sync-lock"
private const val TOKEN_FILE = "lock.json"
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
        refreshRoot(root)
        val existing = root.findFile(LOCK_DIR)

        if (existing != null) {
            val data = readLockData(existing)
            if (data != null) {
                val age = System.currentTimeMillis() - data.heartbeat
                // A fresh lock held by ANOTHER device means that device is
                // syncing — back off. A fresh lock held by US is an orphan
                // from a crashed previous sync (syncs here are serialized by
                // the isSyncing guard), so reclaim it by deleting and
                // re-creating below instead of waiting out the full stale
                // timeout.
                if (age < STALE_TIMEOUT_MS && data.lockedBy != deviceId) {
                    return@withContext false
                }
            }
            deleteLockDir(existing)
        }

        claimToken = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val lock = LockData(
            lockedBy = deviceId,
            claimToken = claimToken,
            acquiredAt = now,
            heartbeat = now,
        )
        // The atomic step: only one device can own this directory name.
        // Any other device's createDirectory (even from a stale listing)
        // fails on the server and comes back null, so a non-null result
        // means we won the lock outright.
        val dir = root.createDirectory(LOCK_DIR) ?: return@withContext false

        val file = dir.createFile("application/json", TOKEN_FILE) ?: return@withContext false
        context.contentResolver.openOutputStream(file.uri)?.use {
            it.write(json.encodeToString(lock).toByteArray(Charsets.UTF_8))
        } ?: return@withContext false

        // We are the only writer inside this directory, so a read-back that
        // shows our token is guaranteed to keep showing it until we release.
        val verification = readLockData(dir)
        verification != null && verification.lockedBy == deviceId && verification.claimToken == claimToken
    }

    private fun refreshRoot(root: DocumentFile) {
        try {
            context.contentResolver.refresh(root.uri, null, null)
        } catch (_: Throwable) {}
    }

    suspend fun heartbeat() {
        if (treeUri.isBlank() || deviceId.isBlank() || claimToken.isBlank()) return
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext
            val dir = root.findFile(LOCK_DIR) ?: return@withContext
            val data = readLockData(dir) ?: return@withContext
            if (data.lockedBy != deviceId || data.claimToken != claimToken) return@withContext
            val updated = data.copy(heartbeat = System.currentTimeMillis())
            val file = dir.findFile(TOKEN_FILE) ?: return@withContext
            context.contentResolver.openOutputStream(file.uri)?.use {
                it.write(json.encodeToString(updated).toByteArray(Charsets.UTF_8))
            }
        }
    }

    suspend fun release() {
        if (treeUri.isBlank()) return
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext
            // The listing may still show the pre-lock state; refresh before
            // looking the lock up so we actually delete the directory we made.
            refreshRoot(root)
            val dir = root.findFile(LOCK_DIR) ?: return@withContext
            val data = readLockData(dir)
            if (data != null && data.lockedBy == deviceId && data.claimToken == claimToken) {
                deleteLockDir(dir)
            }
        }
    }

    private fun readLockData(dir: DocumentFile): LockData? {
        val file = dir.findFile(TOKEN_FILE) ?: return null
        if (!file.exists()) return null
        val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: return null
        return try {
            json.decodeFromString(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteLockDir(dir: DocumentFile) {
        val token = dir.findFile(TOKEN_FILE)
        if (token != null) {
            try {
                token.delete()
            } catch (_: Exception) {}
        }
        try {
            dir.delete()
        } catch (_: Exception) {}
    }
}