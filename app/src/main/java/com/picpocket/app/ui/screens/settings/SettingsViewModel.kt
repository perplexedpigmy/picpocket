package com.picpocket.app.ui.screens.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.scan.QualityTier
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
            )
        }
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
