package com.picpocket.app.drive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveAuthManager @Inject constructor() {
    private val _authState = MutableStateFlow<DriveAuthState>(DriveAuthState.Disconnected)
    val authState: StateFlow<DriveAuthState> = _authState.asStateFlow()

    fun setConnected() {
        _authState.value = DriveAuthState.Connected
    }

    fun setDisconnected() {
        _authState.value = DriveAuthState.Disconnected
    }

    fun checkExistingAuth() {
        // State is managed externally based on folder selection
    }

    fun signOut() {
        _authState.value = DriveAuthState.Disconnected
    }
}
