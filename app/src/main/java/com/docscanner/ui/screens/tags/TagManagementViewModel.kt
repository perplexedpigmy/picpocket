package com.docscanner.ui.screens.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.data.model.Tag
import com.docscanner.data.model.TagAutomation
import com.docscanner.data.repository.DocumentRepository
import com.docscanner.util.fuzzyMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TagManagementUiState(
    val allTags: List<Tag> = emptyList(),
    val searchQuery: String = "",
    val selectionMode: Boolean = false,
    val selectedTagIds: Set<Long> = emptySet(),
    val showCreateDialog: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val pendingDeleteTagId: Long? = null,
    val editingTagId: Long? = null,
    val editingTagName: String = "",
    val detailSheetTagId: Long? = null,
    val detailSheetTag: Tag? = null,
    val detailSheetAutomations: List<TagAutomation> = emptyList(),
    val showWorkflowConfig: Boolean = false,
)

@HiltViewModel
@OptIn(FlowPreview::class)
class TagManagementViewModel @Inject constructor(
    private val repository: DocumentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TagManagementUiState())
    val uiState: StateFlow<TagManagementUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _debouncedQuery = _searchQuery.debounce(200)
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        viewModelScope.launch {
            combine(
                repository.observeAllTags(),
                _debouncedQuery,
            ) { tags, query ->
                if (query.isBlank()) tags
                else tags.filter { fuzzyMatch(query, it.name) }
            }.collect { filtered ->
                _uiState.update {
                    it.copy(
                        allTags = filtered,
                        selectedTagIds = it.selectedTagIds.filter { id ->
                            filtered.any { t -> t.id == id }
                        }.toSet(),
                    )
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun showCreateDialog() {
        _uiState.update { it.copy(showCreateDialog = true) }
    }

    fun hideCreateDialog() {
        _uiState.update { it.copy(showCreateDialog = false) }
    }

    fun createTag(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createTag(name.trim())
            _uiState.update { it.copy(showCreateDialog = false) }
        }
    }

    fun startEditing(tagId: Long) {
        val tag = _uiState.value.allTags.find { it.id == tagId } ?: return
        _uiState.update { it.copy(editingTagId = tagId, editingTagName = tag.name) }
    }

    fun updateEditingName(name: String) {
        _uiState.update { it.copy(editingTagName = name) }
    }

    fun saveEdit() {
        val tagId = _uiState.value.editingTagId ?: return
        val name = _uiState.value.editingTagName.trim()
        if (name.isBlank()) {
            cancelEdit()
            return
        }
        viewModelScope.launch {
            repository.renameTag(tagId, name)
            cancelEdit()
        }
    }

    fun cancelEdit() {
        _uiState.update { it.copy(editingTagId = null, editingTagName = "") }
    }

    fun enterSelectionMode(tagId: Long) {
        _uiState.update {
            it.copy(
                selectionMode = true,
                selectedTagIds = setOf(tagId),
            )
        }
    }

    fun toggleSelection(tagId: Long) {
        _uiState.update { state ->
            val newSelection = if (tagId in state.selectedTagIds) {
                state.selectedTagIds - tagId
            } else {
                state.selectedTagIds + tagId
            }
            state.copy(
                selectedTagIds = newSelection,
                selectionMode = newSelection.isNotEmpty(),
            )
        }
    }

    fun exitSelectionMode() {
        _uiState.update {
            it.copy(selectionMode = false, selectedTagIds = emptySet())
        }
    }

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    fun showDeleteConfirmationForTag(tagId: Long) {
        _uiState.update { it.copy(showDeleteConfirmation = true, pendingDeleteTagId = tagId) }
    }

    fun hideDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false, pendingDeleteTagId = null) }
    }

    fun confirmDelete() {
        val tagId = _uiState.value.pendingDeleteTagId
        val ids = if (tagId != null) listOf(tagId)
            else _uiState.value.selectedTagIds.toList()
        if (ids.isEmpty()) {
            hideDeleteConfirmation()
            return
        }
        viewModelScope.launch {
            repository.deleteTags(ids)
            if (tagId != null) {
                _uiState.update { it.copy(showDeleteConfirmation = false, pendingDeleteTagId = null) }
            } else {
                exitSelectionMode()
                hideDeleteConfirmation()
            }
        }
    }

    fun showDetailSheet(tagId: Long) {
        val tag = _uiState.value.allTags.find { it.id == tagId } ?: return
        _uiState.update { it.copy(detailSheetTagId = tagId, detailSheetTag = tag) }
        viewModelScope.launch {
            repository.observeTagAutomations(tagId).collect { automations ->
                val current = _uiState.value
                if (current.detailSheetTagId == tagId) {
                    _uiState.update { it.copy(detailSheetAutomations = automations) }
                }
            }
        }
    }

    fun hideDetailSheet() {
        _uiState.update { it.copy(detailSheetTagId = null, detailSheetTag = null, detailSheetAutomations = emptyList()) }
    }

    fun renameDetailTag(name: String) {
        val tagId = _uiState.value.detailSheetTagId ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.renameTag(tagId, name)
        }
    }

    fun showWorkflowConfig() {
        _uiState.update { it.copy(showWorkflowConfig = true) }
    }

    fun hideWorkflowConfig() {
        _uiState.update { it.copy(showWorkflowConfig = false) }
    }

    fun createWorkflow(automation: TagAutomation) {
        viewModelScope.launch {
            repository.createAutomation(automation)
            hideWorkflowConfig()
        }
    }

    fun deleteWorkflow(id: Long) {
        viewModelScope.launch {
            repository.deleteAutomation(id)
        }
    }
}
