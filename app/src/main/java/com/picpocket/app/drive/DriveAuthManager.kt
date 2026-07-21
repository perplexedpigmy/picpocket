package com.picpocket.app.drive

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _authState = MutableStateFlow<DriveAuthState>(DriveAuthState.Disconnected)
    val authState: StateFlow<DriveAuthState> = _authState.asStateFlow()

    private val signInClient: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, options)
    }

    val signInIntent: Intent
        get() = signInClient.signInIntent

    fun handleSignInResult(result: ActivityResult) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            task.getResult(ApiException::class.java)
            _authState.value = DriveAuthState.Connected
        } catch (e: ApiException) {
            _authState.value = DriveAuthState.Error(e.localizedMessage ?: "Sign in failed")
        }
    }

    fun checkExistingAuth() {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        _authState.value = if (account != null) {
            DriveAuthState.Connected
        } else {
            DriveAuthState.Disconnected
        }
    }

    suspend fun signOut() {
        signInClient.signOut().await()
        _authState.value = DriveAuthState.Disconnected
    }

    suspend fun refreshAuth() {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account != null) {
                _authState.value = DriveAuthState.Connected
            } else {
                _authState.value = DriveAuthState.ReauthRequired
            }
        } catch (_: Exception) {
            _authState.value = DriveAuthState.ReauthRequired
        }
    }
}
