package com.docscanner.ui.screens.detail

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.data.model.Document
import com.docscanner.data.model.Page
import com.docscanner.data.model.Tag
import com.docscanner.data.repository.DocumentRepository
import com.docscanner.di.SearchablePdf
import com.docscanner.domain.pdf.PageSize
import com.docscanner.domain.pdf.PdfGenerator
import com.docscanner.domain.pdf.PdfResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val document: Document? = null,
    val pages: List<Page> = emptyList(),
    val reorderablePages: List<Page> = emptyList(),
    val isLoading: Boolean = true,
    val showRenameDialog: Boolean = false,
    val renameText: String = "",
    val isEditMode: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val showTagsSheet: Boolean = false,
    val selectedTagIds: Set<Long> = emptySet(),
    val documentTags: List<Tag> = emptyList(),
)

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    application: Application,
    private val repository: DocumentRepository,
    @SearchablePdf private val searchablePdf: PdfGenerator,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private val prefs = getApplication<Application>().getSharedPreferences("settings", 0)

    private var currentDocumentId: Long = 0

    private val _allTags = repository.observeAllTags()
    val allTags: StateFlow<List<Tag>> = _allTags.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    fun loadDocument(documentId: Long) {
        currentDocumentId = documentId
        viewModelScope.launch {
            repository.observeDocument(documentId).collect { doc ->
                _uiState.update { it.copy(document = doc) }
            }
        }
        viewModelScope.launch {
            repository.observePages(documentId).collect { pages ->
                val current = _uiState.value
                if (!current.isEditMode || current.reorderablePages.isEmpty()) {
                    _uiState.update { it.copy(pages = pages, reorderablePages = pages, isLoading = false) }
                } else {
                    _uiState.update { it.copy(pages = pages, isLoading = false) }
                }
            }
        }
        viewModelScope.launch {
            repository.observeDocumentTags(documentId).collect { tags ->
                _uiState.update { it.copy(documentTags = tags) }
            }
        }
    }

    fun showTagsSheet() {
        _uiState.update { it.copy(
            showTagsSheet = true,
            selectedTagIds = it.documentTags.map { it.id }.toSet(),
        ) }
    }

    fun hideTagsSheet() {
        _uiState.update { it.copy(showTagsSheet = false) }
    }

    fun toggleTag(tagId: Long) {
        _uiState.update { state ->
            val newSet = if (tagId in state.selectedTagIds) {
                state.selectedTagIds - tagId
            } else {
                state.selectedTagIds + tagId
            }
            state.copy(selectedTagIds = newSet)
        }
    }

    fun createTagAndSelect(name: String) {
        viewModelScope.launch {
            val tagId = repository.createTag(name)
            _uiState.update { it.copy(selectedTagIds = it.selectedTagIds + tagId) }
        }
    }

    fun applyTags() {
        val docId = currentDocumentId
        if (docId == 0L) return
        val tagIds = _uiState.value.selectedTagIds.toList()
        viewModelScope.launch {
            repository.setDocumentTags(docId, tagIds)
            _uiState.update { it.copy(showTagsSheet = false) }
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
        val state = _uiState.value
        if (state.isEditMode) {
            val docId = state.document?.id ?: return
            viewModelScope.launch {
                repository.reorderPages(docId, state.reorderablePages.map { it.id })
            }
        }
        _uiState.update { it.copy(isEditMode = !it.isEditMode) }
    }

    fun reorderLocally(fromIndex: Int, toIndex: Int) {
        val pages = _uiState.value.reorderablePages.toMutableList()
        if (fromIndex < 0 || fromIndex >= pages.size) return
        if (toIndex < 0 || toIndex >= pages.size) return
        val item = pages.removeAt(fromIndex)
        pages.add(toIndex, item)
        _uiState.update { it.copy(reorderablePages = pages) }
    }

    fun deletePage(pageId: Long) {
        val docId = _uiState.value.document?.id ?: return
        viewModelScope.launch {
            repository.deletePage(pageId)
            _uiState.update {
                it.copy(reorderablePages = it.reorderablePages.filter { p -> p.id != pageId })
            }
            val remainingPages = repository.getPages(docId)
            if (remainingPages.isNotEmpty()) {
                val doc = repository.getDocument(docId)
                val outputUri = doc?.outputUri ?: return@launch
                val app = getApplication<Application>()
                val savedSize = prefs.getString("page_size", PageSize.A4.name) ?: PageSize.A4.name
                val pageSize = try { PageSize.valueOf(savedSize) } catch (_: Exception) { PageSize.A4 }
                val result = searchablePdf.generate(app, remainingPages, Uri.parse(outputUri), pageSize)
                when (result) {
                    is PdfResult.Success -> repository.updateDocumentOutputUri(docId, result.uri)
                    is PdfResult.Error -> { /* PDF regeneration failed silently */ }
                }
            }
        }
    }

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun deleteDocument() {
        val docId = _uiState.value.document?.id ?: return
        viewModelScope.launch {
            repository.deleteDocument(docId)
        }
    }

    fun movePage(pageId: Long, newIndex: Int) {
        val docId = _uiState.value.document?.id ?: return
        val pages = _uiState.value.pages.toMutableList()
        val currentIndex = pages.indexOfFirst { it.id == pageId }
        if (currentIndex < 0) return
        val page = pages.removeAt(currentIndex)
        pages.add(newIndex, page)
        viewModelScope.launch {
            repository.reorderPages(docId, pages.map { it.id })
        }
    }

    fun reorderPages(pageIds: List<Long>) {
        val docId = _uiState.value.document?.id ?: return
        viewModelScope.launch {
            repository.reorderPages(docId, pageIds)
        }
    }
}
