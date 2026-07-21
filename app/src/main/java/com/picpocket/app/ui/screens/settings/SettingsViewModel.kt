package com.picpocket.app.ui.screens.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
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
import com.picpocket.app.drive.sync.SyncSettings
import com.picpocket.app.ui.theme.DarkMode
import com.picpocket.app.ui.theme.Palette
import com.picpocket.app.ui.theme.ThemeManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
)

sealed interface FolderPickerState {
    data object Idle : FolderPickerState
    data object FolderPickRequired : FolderPickerState
    data object Confirmed : FolderPickerState
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
    }

    val signInIntent: Intent
        get() = driveAuthManager.signInIntent

    fun handleSignInResult(result: ActivityResult) {
        driveAuthManager.handleSignInResult(result)
        if (driveAuthManager.authState.value is DriveAuthState.Connected) {
            if (localDriveIndex.getRootFolderId().isNotBlank()) {
                _folderPickerState.value = FolderPickerState.Confirmed
            } else {
                _folderPickerState.value = FolderPickerState.FolderPickRequired
            }
        }
    }

    fun handleFolderPickerResult(uri: Uri?) {
        if (uri == null || uri.authority != "com.google.android.apps.docs.storage") {
            _folderPickerState.value = FolderPickerState.Error(
                if (uri == null) "Folder selection cancelled"
                else "Please select a folder from Google Drive"
            )
            return
        }
        val folderId = Uri.decode(uri.lastPathSegment ?: "")
        if (folderId.isBlank()) {
            _folderPickerState.value = FolderPickerState.Error("Could not identify selected folder")
            return
        }
        localDriveIndex.setRootFolderId(folderId)
        _folderPickerState.value = FolderPickerState.Confirmed
    }

    fun folderPickLaunched() {
        _folderPickerState.value = FolderPickerState.Idle
    }

    fun dismissError() {
        _folderPickerState.value = FolderPickerState.Idle
    }

    fun signOut() {
        viewModelScope.launch {
            driveAuthManager.signOut()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            driveAuthManager.refreshAuth()
        }
    }

    fun setEncryptionPassphrase(passphrase: String) {
        encryptionManager.setPassphrase(passphrase)
        passphraseStore.savePassphrase(passphrase)
        _uiState.update { it.copy() }
    }

    fun disableEncryption() {
        encryptionManager.clearPassphrase()
        passphraseStore.clearPassphrase()
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
}
