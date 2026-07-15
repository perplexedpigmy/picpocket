package com.docscanner.ui.screens.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.data.repository.DocumentRepository
import com.docscanner.ui.theme.DarkMode
import com.docscanner.ui.theme.Palette
import com.docscanner.ui.theme.ThemeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import com.docscanner.domain.pdf.PageSize

data class CacheEntry(val docName: String, val pageCount: Int, val bytes: Long)

sealed class CleanupState {
    object IDLE : CleanupState()
    data class CACHE_INFO(val entries: List<CacheEntry>, val totalBytes: Long) : CleanupState()
    data class DONE(val message: String) : CleanupState()
}

data class SettingsUiState(
    val searchablePdf: Boolean = true,
    val defaultSaveUri: String? = null,
    val defaultSaveLabel: String = "Not set",
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val palette: Palette = Palette.DEFAULT,
    val pageSize: PageSize = PageSize.A4,
    val cleanupState: CleanupState = CleanupState.IDLE,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val themeManager: ThemeManager,
    private val repository: DocumentRepository,
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
        val app = getApplication<Application>()
        app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val label = getDisplayName(app, uri)
        prefs.edit()
            .putString("default_save_uri", uri.toString())
            .putString("default_save_label", label)
            .apply()
        _uiState.update {
            it.copy(
                defaultSaveUri = uri.toString(),
                defaultSaveLabel = label,
            )
        }
    }

    private fun getDisplayName(app: Application, uri: Uri): String {
        val fromDocFile = try {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(app, uri)?.name
        } catch (_: Exception) { null }
        if (fromDocFile != null) return fromDocFile
        val fromContract = try {
            val treeDocId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, treeDocId)
            app.contentResolver.query(
                docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Throwable) { null }
        if (fromContract != null) return fromContract
        return uri.lastPathSegment?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?.substringAfter(":")
            ?: "Selected folder"
    }

    fun dismissCleanupDone() {
        _uiState.update { it.copy(cleanupState = CleanupState.IDLE) }
    }

    fun loadCacheInfo() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val entries = withContext(Dispatchers.IO) {
                val pagesDir = File(app.cacheDir, "pages")
                if (!pagesDir.exists()) return@withContext emptyList<CacheEntry>()
                pagesDir.listFiles()?.mapNotNull { dir ->
                    val id = dir.name.toLongOrNull() ?: return@mapNotNull null
                    val pages = dir.listFiles()?.filter { it.extension == "jpg" } ?: emptyList()
                    if (pages.isEmpty()) return@mapNotNull null
                    val bytes = pages.sumOf { it.length() }
                    val docName = repository.getAllDocuments().find { it.id == id }?.name ?: "doc_$id"
                    CacheEntry(docName, pages.size, bytes)
                } ?: emptyList()
            }
            if (entries.isEmpty()) {
                _uiState.update { it.copy(cleanupState = CleanupState.DONE("Cache is empty.")) }
            } else {
                _uiState.update {
                    it.copy(cleanupState = CleanupState.CACHE_INFO(entries, entries.sumOf { it.bytes }))
                }
            }
        }
    }

    fun confirmCleanCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                File(getApplication<Application>().cacheDir, "pages").deleteRecursively()
            }
            _uiState.update { it.copy(cleanupState = CleanupState.DONE("Cache cleared.")) }
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
