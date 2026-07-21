package com.picpocket.app.drive

sealed interface DriveAuthState {
    data object Disconnected : DriveAuthState
    data object Connected : DriveAuthState
    data object ReauthRequired : DriveAuthState
    data class Error(val message: String) : DriveAuthState
    data object Connecting : DriveAuthState
}
