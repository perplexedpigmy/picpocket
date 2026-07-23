package com.picpocket.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.debug.Category
import com.picpocket.app.debug.Level
import com.picpocket.app.debug.Tracing
import com.picpocket.app.debug.TracingBuffer
import com.picpocket.app.debug.TracingConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TracingUiState(
    val globalEnabled: Boolean = false,
    val categoryLevels: Map<Category, Level> = Category.entries.associateWith { it.defaultLevel },
)

@HiltViewModel
class TracingViewModel @Inject constructor(
    application: Application,
    private val tracingConfig: TracingConfig,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(TracingUiState())
    val uiState: StateFlow<TracingUiState> = _uiState.asStateFlow()

    init {
        val overrides = Category.entries.associateWith { category ->
            tracingConfig.getOverrideLevel(category) ?: category.defaultLevel
        }
        _uiState.update {
            TracingUiState(
                globalEnabled = tracingConfig.globalEnabled,
                categoryLevels = overrides,
            )
        }
    }

    fun toggleGlobal(enabled: Boolean) {
        tracingConfig.setGlobalEnabled(enabled)
        _uiState.update { it.copy(globalEnabled = enabled) }
    }

    fun setCategoryLevel(category: Category, level: Level) {
        val default = category.defaultLevel
        if (level == default) {
            tracingConfig.setOverrideLevel(category, null)
        } else {
            tracingConfig.setOverrideLevel(category, level)
        }
        _uiState.update {
            it.copy(categoryLevels = it.categoryLevels + (category to level))
        }
    }

    fun clearLogs() {
        TracingBuffer.clear()
    }

    fun dumpStoreState() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val TAG = "StoreDump"
            val docsRoot = java.io.File(app.filesDir, "documents")
            Tracing.i(Category.STORE_STATE, TAG, "=== Store State Dump ===")
            Tracing.i(Category.STORE_STATE, TAG, "Documents root: ${docsRoot.absolutePath}  exists=${docsRoot.exists()}")
            if (!docsRoot.exists()) {
                Tracing.i(Category.STORE_STATE, TAG, "No documents directory found")
                return@launch
            }
            val dirs = docsRoot.listFiles() ?: run {
                Tracing.w(Category.STORE_STATE, TAG, "listFiles returned null")
                return@launch
            }
            Tracing.i(Category.STORE_STATE, TAG, "Found ${dirs.size} document dir(s)")
            for (docDir in dirs.sortedBy { it.name }) {
                if (!docDir.isDirectory) continue
                val files = docDir.listFiles() ?: emptyArray()
                val totalSize = files.sumOf { it.length() }
                Tracing.d(Category.STORE_STATE, TAG, "  ${docDir.name}/  (${files.size} files, ${totalSize} bytes)")
                for (f in files.sortedBy { it.name }) {
                    Tracing.v(Category.STORE_STATE, TAG, "    ${f.name}  ${f.length()} bytes")
                }
                val metaFile = java.io.File(docDir, "metadata.json")
                if (metaFile.exists()) {
                    try {
                        val raw = metaFile.readText()
                        Tracing.d(Category.STORE_STATE, TAG, "    --- metadata.json content ---")
                        for (line in raw.lines()) {
                            Tracing.v(Category.STORE_STATE, TAG, "    | $line")
                        }
                        Tracing.d(Category.STORE_STATE, TAG, "    --- end metadata.json ---")
                    } catch (e: Exception) {
                        Tracing.w(Category.STORE_STATE, TAG, "    metadata.json read error: ${e.message}")
                    }
                } else {
                    Tracing.d(Category.STORE_STATE, TAG, "    [missing metadata.json]")
                }
            }
            val indexFile = java.io.File(app.filesDir, "drive_index.json")
            if (indexFile.exists()) {
                try {
                    Tracing.i(Category.STORE_STATE, TAG, "drive_index.json (${indexFile.length()} bytes):")
                    for (line in indexFile.readText().lines()) {
                        Tracing.v(Category.STORE_STATE, TAG, "  | $line")
                    }
                } catch (e: Exception) {
                    Tracing.w(Category.STORE_STATE, TAG, "drive_index.json read error: ${e.message}")
                }
            } else {
                Tracing.i(Category.STORE_STATE, TAG, "No drive_index.json found")
            }
            val journalFile = java.io.File(app.filesDir, "sync_journal.json")
            if (journalFile.exists()) {
                Tracing.i(Category.STORE_STATE, TAG, "sync_journal.json: ${journalFile.length()} bytes")
            } else {
                Tracing.i(Category.STORE_STATE, TAG, "No sync_journal.json found")
            }
            Tracing.i(Category.STORE_STATE, TAG, "=== End Dump ===")
        }
    }
}
