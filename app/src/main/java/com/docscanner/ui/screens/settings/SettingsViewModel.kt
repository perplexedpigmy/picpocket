package com.docscanner.ui.screens.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.ui.theme.DarkMode
import com.docscanner.ui.theme.Palette
import com.docscanner.ui.theme.ThemeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.docscanner.domain.pdf.PageSize

data class SettingsUiState(
    val searchablePdf: Boolean = true,
    val defaultSaveUri: String? = null,
    val defaultSaveLabel: String = "Not set",
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val palette: Palette = Palette.DEFAULT,
    val pageSize: PageSize = PageSize.A4,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val themeManager: ThemeManager,
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("settings", 0)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        val savedSize = prefs.getString("page_size", PageSize.A4.name) ?: PageSize.A4.name
        val pageSize = try { PageSize.valueOf(savedSize) } catch (_: Exception) { PageSize.A4 }
        val config = themeManager.config.value
        _uiState.update {
            it.copy(
                searchablePdf = prefs.getBoolean("searchable_pdf", true),
                defaultSaveUri = prefs.getString("default_save_uri", null),
                defaultSaveLabel = prefs.getString("default_save_label", "Not set") ?: "Not set",
                pageSize = pageSize,
                darkMode = config.darkMode,
                palette = config.palette,
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
}
