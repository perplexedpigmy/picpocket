package com.docscanner.ui.screens.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.data.model.Page
import com.docscanner.data.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ViewerUiState(
    val pages: List<Page> = emptyList(),
    val currentIndex: Int = 0,
    val isLoading: Boolean = true,
)

@HiltViewModel
class PageViewerViewModel @Inject constructor(
    private val repository: DocumentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    fun loadPages(documentId: Long, initialPageIndex: Int = 0) {
        viewModelScope.launch {
            repository.observePages(documentId).collect { pages ->
                _uiState.update {
                    it.copy(pages = pages, isLoading = false)
                }
            }
        }
        _uiState.update { it.copy(currentIndex = initialPageIndex) }
    }

    fun setPageIndex(index: Int) {
        _uiState.update { it.copy(currentIndex = index) }
    }
}
