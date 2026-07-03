package com.docscanner.ui.screens.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val searchablePdf: Boolean = true,
    val defaultSaveUri: String? = null,
    val defaultSaveLabel: String = "Not set",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("settings", 0)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                searchablePdf = prefs.getBoolean("searchable_pdf", true),
                defaultSaveUri = prefs.getString("default_save_uri", null),
                defaultSaveLabel = prefs.getString("default_save_label", "Not set") ?: "Not set",
            )
        }
    }

    fun toggleSearchablePdf(enabled: Boolean) {
        prefs.edit().putBoolean("searchable_pdf", enabled).apply()
        _uiState.update { it.copy(searchablePdf = enabled) }
    }

    fun setDefaultSaveUri(uri: Uri) {
        prefs.edit()
            .putString("default_save_uri", uri.toString())
            .putString("default_save_label", uri.lastPathSegment ?: "Selected folder")
            .apply()
        _uiState.update {
            it.copy(
                defaultSaveUri = uri.toString(),
                defaultSaveLabel = uri.lastPathSegment ?: "Selected folder",
            )
        }
    }
}
