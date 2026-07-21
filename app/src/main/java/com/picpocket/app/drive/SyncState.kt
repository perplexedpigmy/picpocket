package com.picpocket.app.drive

sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Error(val message: String) : SyncState
}
