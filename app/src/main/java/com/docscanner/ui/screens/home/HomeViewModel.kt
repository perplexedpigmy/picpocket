package com.docscanner.ui.screens.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.data.model.Document
import com.docscanner.data.model.Tag
import com.docscanner.data.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortOrder(val label: String) {
    MODIFIED_DESC("Last modified"),
    CREATED_DESC("Created"),
    SIZE_DESC("Size"),
    NAME_ASC("Name"),
}

data class HomeUiState(
    val documents: List<Document> = emptyList(),
    val isLoading: Boolean = true,
    val selectionMode: Boolean = false,
    val selectedDocumentIds: Set<Long> = emptySet(),
    val showDeleteConfirmation: Boolean = false,
    val sortOrder: SortOrder = SortOrder.MODIFIED_DESC,
    val showRenameDialog: Boolean = false,
    val renameText: String = "",
    val searchQuery: String = "",
    val searchInContent: Boolean = false,
    val showTagsSheet: Boolean = false,
    val selectedTagIds: Set<Long> = emptySet(),
    val documentTags: Map<Long, List<Tag>> = emptyMap(),
)

@HiltViewModel
@OptIn(FlowPreview::class)
class HomeViewModel @Inject constructor(
    private val repository: DocumentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private val _sortOrder = MutableStateFlow(SortOrder.MODIFIED_DESC)
    private val _searchQuery = MutableStateFlow("")
    private val _searchInContent = MutableStateFlow(false)
    private val _ocrMatchIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _debouncedQuery = _searchQuery.debounce(300)
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _allTags = repository.observeAllTags()
    val allTags: StateFlow<List<Tag>> = _allTags.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    private val _documentTagMap = repository.observeDocumentTagMap().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyMap(),
    )

    init {
        viewModelScope.launch {
            _documentTagMap.collect { map ->
                _uiState.update { it.copy(documentTags = map) }
            }
        }

        viewModelScope.launch {
            combine(_searchInContent, _debouncedQuery) { a, b -> a to b }
                .collectLatest { (inContent, query) ->
                    if (inContent && query.isNotBlank()) {
                        _ocrMatchIds.value = repository.searchDocumentsByOcrText(query)
                    } else {
                        _ocrMatchIds.value = emptySet()
                    }
                }
        }

        viewModelScope.launch {
            combine(
                repository.observeDocuments(),
                _sortOrder,
                _debouncedQuery,
                _searchInContent,
                _ocrMatchIds,
            ) { docs, sortOrder, query, inContent, ocrIds ->
                val filtered = if (query.isBlank()) docs
                else {
                    val nameMatch = docs.filter {
                        try {
                            Regex(query, RegexOption.IGNORE_CASE).containsMatchIn(it.name)
                        } catch (_: Exception) { false }
                    }
                    if (inContent) (nameMatch + docs.filter { it.id in ocrIds }).distinctBy { it.id }
                    else nameMatch
                }
                val sorted = when (sortOrder) {
                    SortOrder.MODIFIED_DESC -> filtered.sortedByDescending { it.updatedAt }
                    SortOrder.CREATED_DESC -> filtered.sortedByDescending { it.createdAt }
                    SortOrder.SIZE_DESC -> filtered.sortedByDescending { it.totalFileSize }
                    SortOrder.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
                }
                sorted to sortOrder
            }.collect { (sorted, sortOrder) ->
                _uiState.update { state ->
                    state.copy(
                        documents = sorted,
                        sortOrder = sortOrder,
                        isLoading = false,
                        selectedDocumentIds = if (state.selectionMode) {
                            state.selectedDocumentIds.filter { id ->
                                sorted.any { it.id == id }
                            }.toSet()
                        } else emptySet(),
                    )
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleSearchInContent() {
        val new = !_searchInContent.value
        _searchInContent.value = new
        _uiState.update { it.copy(searchInContent = new) }
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
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

    fun toggleSelectAll() {
        val state = _uiState.value
        val allIds = state.documents.map { it.id }.toSet()
        if (state.selectedDocumentIds.size == allIds.size) {
            _uiState.update { it.copy(selectedDocumentIds = emptySet()) }
        } else {
            _uiState.update { it.copy(selectedDocumentIds = allIds) }
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

    fun showRenameDialog() {
        val doc = _uiState.value.selectedDocumentIds.firstOrNull()?.let { id ->
            _uiState.value.documents.find { it.id == id }
        }
        _uiState.update { it.copy(showRenameDialog = true, renameText = doc?.name.orEmpty()) }
    }

    fun hideRenameDialog() {
        _uiState.update { it.copy(showRenameDialog = false) }
    }

    fun updateRenameText(text: String) {
        _uiState.update { it.copy(renameText = text) }
    }

    fun renameSelected() {
        val docId = _uiState.value.selectedDocumentIds.firstOrNull() ?: return
        val name = _uiState.value.renameText
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.updateDocumentName(docId, name)
            hideRenameDialog()
        }
    }

    fun showTagsSheet() {
        _uiState.update { it.copy(showTagsSheet = true, selectedTagIds = emptySet()) }
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

    fun applyTagsToSelected() {
        val docIds = _uiState.value.selectedDocumentIds.toList()
        val tagIds = _uiState.value.selectedTagIds.toList()
        if (docIds.isEmpty()) return
        viewModelScope.launch {
            for (docId in docIds) {
                repository.setDocumentTags(docId, tagIds)
            }
            _uiState.update { it.copy(showTagsSheet = false) }
        }
    }

    fun shareSelected(context: Context) {
        val uris = _uiState.value.selectedDocumentIds.mapNotNull { id ->
            _uiState.value.documents.find { it.id == id }?.outputUri
        }.mapNotNull { uri ->
            try {
                android.net.Uri.parse(uri)
            } catch (_: Exception) { null }
        }
        if (uris.isEmpty()) return

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share PDF(s)"))
    }
}
