package com.picpocket.app.ui.screens.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.scan.QualityTier
import com.picpocket.app.drive.DriveAuthManager
import com.picpocket.app.drive.DriveAuthState
import com.picpocket.app.drive.EncryptionManager
import com.picpocket.app.drive.PassphraseStore
import com.picpocket.app.drive.sync.LocalDriveIndex
import com.picpocket.app.drive.sync.RetryHandler
import com.picpocket.app.drive.sync.SyncManager
import com.picpocket.app.drive.sync.SyncSettings
import com.picpocket.app.ui.theme.DarkMode
import com.picpocket.app.ui.theme.Palette
import com.picpocket.app.ui.theme.ThemeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import com.picpocket.app.drive.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val searchablePdf: Boolean = true,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val palette: Palette = Palette.DEFAULT,
    val pageSize: PageSize = PageSize.A4,
    val qualityTier: QualityTier = QualityTier.BEST,
    val syncEnabled: Boolean = true,
    val syncState: SyncState = SyncState.Idle,
    val hasFolder: Boolean = false,
)

sealed interface FolderPickerState {
    data object Idle : FolderPickerState
    data object Confirmed : FolderPickerState
    data object FolderPickRequired : FolderPickerState
    data class Error(val message: String) : FolderPickerState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val themeManager: ThemeManager,
    private val driveAuthManager: DriveAuthManager,
    private val encryptionManager: EncryptionManager,
    private val passphraseStore: PassphraseStore,
    private val syncSettings: SyncSettings,
    private val localDriveIndex: LocalDriveIndex,
    private val syncManager: SyncManager,
    private val retryHandler: RetryHandler,
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("settings", 0)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val driveAuthState: StateFlow<DriveAuthState> = driveAuthManager.authState

    private val _folderPickerState = MutableStateFlow<FolderPickerState>(FolderPickerState.Idle)
    val folderPickerState: StateFlow<FolderPickerState> = _folderPickerState.asStateFlow()

    val encryptionEnabled: Boolean
        get() = encryptionManager.isEncryptionEnabled

    init {
        val savedPassphrase = passphraseStore.getPassphrase()
        if (!savedPassphrase.isNullOrBlank()) {
            encryptionManager.setPassphrase(savedPassphrase)
        }
        val savedSize = prefs.getString("page_size", PageSize.A4.name) ?: PageSize.A4.name
        val pageSize = try { PageSize.valueOf(savedSize) } catch (_: Exception) { PageSize.A4 }
        val savedQuality = prefs.getInt("quality_tier", QualityTier.BEST.ordinal)
        val qualityTier = QualityTier.entries.getOrElse(savedQuality) { QualityTier.BEST }
        val config = themeManager.config.value
        _uiState.update {
            it.copy(
                searchablePdf = prefs.getBoolean("searchable_pdf", true),
                pageSize = pageSize,
                qualityTier = qualityTier,
                darkMode = config.darkMode,
                palette = config.palette,
                syncEnabled = syncSettings.syncEnabled,
            )
        }
        driveAuthManager.checkExistingAuth()
        val rootUri = localDriveIndex.getRootTreeUri()
        val hasFolder = localDriveIndex.hasValidFolder()
        Log.d(TAG, "init: connected=${driveAuthManager.authState.value is DriveAuthState.Connected} rootTreeUri='$rootUri' hasFolder=$hasFolder")
        _uiState.update { it.copy(hasFolder = hasFolder) }
        Log.d(TAG, "init: state=${driveAuthManager.authState.value}")
        viewModelScope.launch {
            syncManager.syncState.collect { state ->
                _uiState.update { it.copy(syncState = state) }
            }
        }
    }

    val signInIntent: Intent
        get() = driveAuthManager.signInIntent

    fun handleSignInResult(result: ActivityResult) {
        driveAuthManager.handleSignInResult(result)
        if (driveAuthManager.authState.value is DriveAuthState.Connected) {
            if (localDriveIndex.hasValidFolder()) {
                _folderPickerState.value = FolderPickerState.Confirmed
            } else {
                _folderPickerState.value = FolderPickerState.FolderPickRequired
            }
        }
    }

    fun handleFolderPickerResult(uri: Uri?) {
        Log.d(TAG, "handleFolderPickerResult: uri=$uri path=${uri?.path} segments=${uri?.pathSegments}")
        if (uri == null || uri.authority != "com.google.android.apps.docs.storage") {
            _folderPickerState.value = FolderPickerState.Error(
                if (uri == null) "Folder selection cancelled"
                else "Please select a folder from Google Drive"
            )
            return
        }
        val uriString = uri.toString()
        Log.d(TAG, "handleFolderPickerResult: saving tree URI")
        retryHandler.reset()
        localDriveIndex.setRootTreeUri(uriString)
        _folderPickerState.value = FolderPickerState.Confirmed
        _uiState.update { it.copy(hasFolder = true) }
        viewModelScope.launch {
            syncManager.performSync()
        }
    }

    fun dismissError() {
        _folderPickerState.value = FolderPickerState.Idle
    }

    fun signOut() {
        localDriveIndex.clearFolder()
        _uiState.update { it.copy(hasFolder = false) }
        viewModelScope.launch {
            driveAuthManager.signOut()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            retryHandler.reset()
            syncManager.performSync()
        }
    }

    fun setEncryptionPassphrase(passphrase: String) {
        encryptionManager.setPassphrase(passphrase)
        passphraseStore.savePassphrase(passphrase)
        syncManager.synthesizeReEncryptPass()
        viewModelScope.launch { syncManager.performSync() }
        _uiState.update { it.copy() }
    }

    fun disableEncryption() {
        encryptionManager.clearPassphrase()
        passphraseStore.clearPassphrase()
        syncManager.synthesizeReEncryptPass()
        viewModelScope.launch { syncManager.performSync() }
        _uiState.update { it.copy() }
    }

    fun toggleSearchablePdf(enabled: Boolean) {
        prefs.edit().putBoolean("searchable_pdf", enabled).apply()
        _uiState.update { it.copy(searchablePdf = enabled) }
    }

    fun setDarkMode(mode: DarkMode) {
        themeManager.setDarkMode(mode)
        _uiState.update { it.copy(darkMode = mode) }
    }

    fun setPalette(palette: Palette) {
        themeManager.setPalette(palette)
        _uiState.update { it.copy(palette = palette) }
    }

    fun setPageSize(size: PageSize) {
        prefs.edit().putString("page_size", size.name).apply()
        _uiState.update { it.copy(pageSize = size) }
    }

    fun setQualityTier(tier: QualityTier) {
        prefs.edit().putInt("quality_tier", tier.ordinal).apply()
        _uiState.update { it.copy(qualityTier = tier) }
    }

    fun toggleSync(enabled: Boolean) {
        syncSettings.syncEnabled = enabled
        _uiState.update { it.copy(syncEnabled = enabled) }
    }

    fun dumpDocumentDir() {
        val app = getApplication<Application>()
        val docsRoot = java.io.File(app.filesDir, "documents")
        Log.d(TAG, "=== Document Dir Dump ===")
        Log.d(TAG, "Root: ${docsRoot.absolutePath}  exists=${docsRoot.exists()}")
        if (!docsRoot.exists()) {
            Log.d(TAG, "No documents directory found")
            return
        }
        val dirs = docsRoot.listFiles() ?: run {
            Log.d(TAG, "listFiles returned null")
            return
        }
        Log.d(TAG, "Found ${dirs.size} document dir(s)")
        for (docDir in dirs.sortedBy { it.name }) {
            if (!docDir.isDirectory) continue
            val files = docDir.listFiles() ?: emptyArray()
            val totalSize = files.sumOf { it.length() }
            Log.d(TAG, "  ${docDir.name}/  (${files.size} files, ${totalSize} bytes)")
            for (f in files.sortedBy { it.name }) {
                Log.d(TAG, "    ${f.name}  ${f.length()} bytes")
            }
            val metaFile = java.io.File(docDir, "metadata.json")
            if (metaFile.exists()) {
                try {
                    val raw = metaFile.readText()
                    Log.d(TAG, "    --- metadata.json content ---")
                    for (line in raw.lines()) {
                        Log.d(TAG, "    | $line")
                    }
                    Log.d(TAG, "    --- end metadata.json ---")
                } catch (e: Exception) {
                    Log.d(TAG, "    metadata.json read error: ${e.message}")
                }
            } else {
                Log.d(TAG, "    [missing metadata.json]")
            }
        }
        val indexFile = java.io.File(app.filesDir, "drive_index.json")
        if (indexFile.exists()) {
            try {
                Log.d(TAG, "=== drive_index.json (${indexFile.length()} bytes) ===")
                for (line in indexFile.readText().lines()) {
                    Log.d(TAG, "| $line")
                }
            } catch (e: Exception) {
                Log.d(TAG, "drive_index.json read error: ${e.message}")
            }
        } else {
            Log.d(TAG, "No drive_index.json found")
        }
        val journalFile = java.io.File(app.filesDir, "sync_journal.json")
        if (journalFile.exists()) {
            Log.d(TAG, "sync_journal.json: ${journalFile.length()} bytes")
        } else {
            Log.d(TAG, "No sync_journal.json found")
        }
        Log.d(TAG, "=== End Dump ===")
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
