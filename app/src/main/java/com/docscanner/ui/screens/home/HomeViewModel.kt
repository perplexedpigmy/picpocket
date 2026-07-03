package com.docscanner.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.data.model.Document
import com.docscanner.data.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val documents: List<Document> = emptyList(),
    val isLoading: Boolean = true,
    val selectionMode: Boolean = false,
    val selectedDocumentIds: Set<Long> = emptySet(),
    val showDeleteConfirmation: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: DocumentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeDocuments().collect { documents ->
                _uiState.update { state ->
                    state.copy(
                        documents = documents,
                        isLoading = false,
                        selectedDocumentIds = if (state.selectionMode) {
                            state.selectedDocumentIds.filter { id ->
                                documents.any { it.id == id }
                            }.toSet()
                        } else emptySet(),
                    )
                }
            }
        }
    }

    fun onDocumentLongPress(documentId: Long) {
        _uiState.update {
            it.copy(
                selectionMode = true,
                selectedDocumentIds = setOf(documentId),
            )
        }
    }

    fun onDocumentTap(documentId: Long): Boolean {
        val state = _uiState.value
        return if (state.selectionMode) {
            toggleSelection(documentId)
            false
        } else {
            true
        }
    }

    fun toggleSelection(documentId: Long) {
        _uiState.update { state ->
            val newSelection = if (documentId in state.selectedDocumentIds) {
                state.selectedDocumentIds - documentId
            } else {
                state.selectedDocumentIds + documentId
            }
            state.copy(
                selectedDocumentIds = newSelection,
                selectionMode = newSelection.isNotEmpty(),
            )
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(
                selectedDocumentIds = state.documents.map { it.id }.toSet(),
            )
        }
    }

    fun exitSelectionMode() {
        _uiState.update {
            it.copy(
                selectionMode = false,
                selectedDocumentIds = emptySet(),
                showDeleteConfirmation = false,
            )
        }
    }

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun deleteSelected() {
        val ids = _uiState.value.selectedDocumentIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.deleteDocuments(ids)
            exitSelectionMode()
        }
    }
}
