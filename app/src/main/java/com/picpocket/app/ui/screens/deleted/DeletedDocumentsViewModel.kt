package com.picpocket.app.ui.screens.deleted

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.drive.sync.DeviceRegistry
import com.picpocket.app.drive.sync.OrphanedDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeletedDocumentsUiState(
    val orphans: List<OrphanedDocument> = emptyList(),
    val processing: Set<String> = emptySet(),
    val cleaning: Boolean = false,
)

@HiltViewModel
class DeletedDocumentsViewModel @Inject constructor(
    private val deviceRegistry: DeviceRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeletedDocumentsUiState())
    val uiState: StateFlow<DeletedDocumentsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(orphans = deviceRegistry.getOrphans()) }
    }

    fun keepOrphan(docId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(processing = it.processing + docId) }
            deviceRegistry.keepOrphan(docId)
            refresh()
        }
    }

    fun deleteOrphanLocally(docId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(processing = it.processing + docId) }
            deviceRegistry.deleteOrphanLocally(docId)
            refresh()
        }
    }

    fun dismissOrphan(docId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(processing = it.processing + docId) }
            deviceRegistry.dismissOrphan(docId)
            refresh()
        }
    }

    fun cleanDrive() {
        viewModelScope.launch {
            _uiState.update { it.copy(cleaning = true) }
            deviceRegistry.cleanDrive()
            refresh()
        }
    }

    private fun refresh() {
        _uiState.update { it.copy(orphans = deviceRegistry.getOrphans(), processing = emptySet(), cleaning = false) }
    }
}
