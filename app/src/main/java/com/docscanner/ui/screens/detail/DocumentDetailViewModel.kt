package com.docscanner.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.data.model.Document
import com.docscanner.data.model.Page
import com.docscanner.data.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val document: Document? = null,
    val pages: List<Page> = emptyList(),
    val isLoading: Boolean = true,
    val showRenameDialog: Boolean = false,
    val renameText: String = "",
    val isEditMode: Boolean = false,
)

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    private val repository: DocumentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    fun loadDocument(documentId: Long) {
        viewModelScope.launch {
            repository.observeDocument(documentId).collect { doc ->
                _uiState.update { it.copy(document = doc) }
            }
        }
        viewModelScope.launch {
            repository.observePages(documentId).collect { pages ->
                _uiState.update { it.copy(pages = pages, isLoading = false) }
            }
        }
    }

    fun showRenameDialog() {
        _uiState.update {
            it.copy(
                showRenameDialog = true,
                renameText = it.document?.name.orEmpty(),
            )
        }
    }

    fun hideRenameDialog() {
        _uiState.update { it.copy(showRenameDialog = false) }
    }

    fun updateRenameText(text: String) {
        _uiState.update { it.copy(renameText = text) }
    }

    fun renameDocument() {
        val docId = _uiState.value.document?.id ?: return
        val name = _uiState.value.renameText
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.updateDocumentName(docId, name)
            hideRenameDialog()
        }
    }

    fun toggleEditMode() {
        _uiState.update { it.copy(isEditMode = !it.isEditMode) }
    }

    fun deletePage(pageId: Long) {
        viewModelScope.launch {
            repository.deletePage(pageId)
        }
    }
}
