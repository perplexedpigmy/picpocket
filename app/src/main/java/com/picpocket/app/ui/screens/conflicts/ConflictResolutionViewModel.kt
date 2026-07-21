package com.picpocket.app.ui.screens.conflicts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.drive.sync.ConflictInfo
import com.picpocket.app.drive.sync.ConflictResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConflictResolutionUiState(
    val conflicts: List<ConflictInfo> = emptyList(),
    val resolving: Set<String> = emptySet(),
)

@HiltViewModel
class ConflictResolutionViewModel @Inject constructor(
    private val conflictResolver: ConflictResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConflictResolutionUiState())
    val uiState: StateFlow<ConflictResolutionUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(conflicts = conflictResolver.getActiveConflicts()) }
    }

    fun keepLocal(docId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(resolving = it.resolving + docId) }
            conflictResolver.resolveConflict(docId, keepLocal = true)
            refresh()
        }
    }

    fun keepRemote(docId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(resolving = it.resolving + docId) }
            conflictResolver.resolveConflict(docId, keepLocal = false)
            refresh()
        }
    }

    fun dismiss(docId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(resolving = it.resolving + docId) }
            conflictResolver.dismissConflict(docId)
            refresh()
        }
    }

    private fun refresh() {
        _uiState.update { it.copy(conflicts = conflictResolver.getActiveConflicts(), resolving = emptySet()) }
    }
}
