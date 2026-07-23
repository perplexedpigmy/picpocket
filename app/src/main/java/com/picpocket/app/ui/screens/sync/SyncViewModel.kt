package com.picpocket.app.ui.screens.sync

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.activity.result.ActivityResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.drive.DriveAuthManager
import com.picpocket.app.drive.DriveAuthState
import com.picpocket.app.drive.EncryptionManager
import com.picpocket.app.drive.PassphraseStore
import com.picpocket.app.drive.SyncState
import com.picpocket.app.drive.sync.ConflictResolver
import com.picpocket.app.drive.sync.DeviceRegistry
import com.picpocket.app.drive.sync.LocalDriveIndex
import com.picpocket.app.drive.sync.RetryHandler
import com.picpocket.app.drive.sync.SyncManager
import com.picpocket.app.drive.sync.SyncSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncUiState(
    val connectionState: ConnectionState = ConnectionState.Loading,
    val syncEnabled: Boolean = false,
    val syncState: SyncState = SyncState.Idle,
    val folderName: String = "",
    val conflictCount: Int = 0,
    val trashCount: Int = 0,
    val removedByOthersCount: Int = 0,
    val encryptionEnabled: Boolean = false,
)

sealed interface ConnectionState {
    data object Loading : ConnectionState
    data object Disconnected : ConnectionState
    data object Connected : ConnectionState
    data class DriveError(val message: String) : ConnectionState
}

sealed interface SyncActionState {
    data object Idle : SyncActionState
    data object SignInRequired : SyncActionState
    data object FolderPickRequired : SyncActionState
    data class Error(val message: String) : SyncActionState
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    application: Application,
    private val driveAuthManager: DriveAuthManager,
    private val syncManager: SyncManager,
    private val syncSettings: SyncSettings,
    private val localDriveIndex: LocalDriveIndex,
    private val conflictResolver: ConflictResolver,
    private val deviceRegistry: DeviceRegistry,
    private val retryHandler: RetryHandler,
    private val encryptionManager: EncryptionManager,
    private val passphraseStore: PassphraseStore,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private val _actionState = MutableStateFlow<SyncActionState>(SyncActionState.Idle)
    val actionState: StateFlow<SyncActionState> = _actionState.asStateFlow()

    val signInIntent: Intent
        get() = driveAuthManager.signInIntent

    init {
        viewModelScope.launch {
            syncManager.syncState.collect { state ->
                _uiState.update { it.copy(syncState = state) }
            }
        }
        val savedPassphrase = passphraseStore.getPassphrase()
        if (!savedPassphrase.isNullOrBlank()) {
            encryptionManager.setPassphrase(savedPassphrase)
        }
        driveAuthManager.checkExistingAuth()
        verifyConnection()
    }

    fun verifyConnection() {
        val authState = driveAuthManager.authState.value
        if (authState !is DriveAuthState.Connected) {
            _uiState.update { it.copy(connectionState = ConnectionState.Disconnected) }
            return
        }
        if (!localDriveIndex.hasValidFolder()) {
            _uiState.update { it.copy(connectionState = ConnectionState.Disconnected) }
            return
        }
        _uiState.update {
            it.copy(
                connectionState = ConnectionState.Connected,
                folderName = localDriveIndex.getRootFolderName(),
                syncEnabled = syncSettings.syncEnabled,
                conflictCount = conflictResolver.getActiveConflicts().size,
                trashCount = deviceRegistry.getMyDeleted().size,
                removedByOthersCount = deviceRegistry.getOthersDeleted().size,
                encryptionEnabled = encryptionManager.isEncryptionEnabled,
            )
        }
    }

    fun setEncryptionPassphrase(passphrase: String) {
        if (syncManager.syncState.value is SyncState.Syncing) return
        encryptionManager.setPassphrase(passphrase)
        passphraseStore.savePassphrase(passphrase)
        viewModelScope.launch {
            syncManager.performSync()
        }
        _uiState.update { it.copy(encryptionEnabled = true) }
    }

    fun disableEncryption() {
        if (syncManager.syncState.value is SyncState.Syncing) return
        encryptionManager.clearPassphrase()
        passphraseStore.clearPassphrase()
        viewModelScope.launch {
            syncManager.synthesizeReEncryptPass()
            syncManager.performSync()
        }
        _uiState.update { it.copy(encryptionEnabled = false) }
    }

    fun handleSignInResult(result: ActivityResult) {
        driveAuthManager.handleSignInResult(result)
        if (driveAuthManager.authState.value is DriveAuthState.Connected) {
            if (localDriveIndex.hasValidFolder()) {
                _actionState.value = SyncActionState.Idle
                verifyConnection()
            } else {
                _actionState.value = SyncActionState.FolderPickRequired
            }
        }
    }

    fun handleFolderPickerResult(uri: Uri?) {
        if (uri == null || uri.authority != "com.google.android.apps.docs.storage") {
            _actionState.value = SyncActionState.Error(
                if (uri == null) "Folder selection cancelled"
                else "Please select a folder from Google Drive",
            )
            return
        }
        val app = getApplication<Application>()
        app.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        retryHandler.reset()
        localDriveIndex.setRootTreeUri(uri.toString())
        val folderName = DocumentFile.fromTreeUri(app, uri)?.name ?: "Drive folder"
        localDriveIndex.setRootFolderName(folderName)
        _actionState.value = SyncActionState.Idle
        verifyConnection()
    }

    fun syncNow() {
        viewModelScope.launch {
            retryHandler.reset()
            syncManager.performSync()
        }
    }

    fun toggleSync(enabled: Boolean) {
        syncSettings.syncEnabled = enabled
        _uiState.update { it.copy(syncEnabled = enabled) }
    }

    fun disconnect() {
        localDriveIndex.clearFolder()
        viewModelScope.launch { driveAuthManager.signOut() }
        _uiState.update { it.copy(connectionState = ConnectionState.Disconnected) }
    }

    fun dismissAction() {
        _actionState.value = SyncActionState.Idle
    }
}
