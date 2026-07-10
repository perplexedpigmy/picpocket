package com.docscanner.ui.screens.home

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.data.model.Document
import com.docscanner.data.model.Tag
import com.docscanner.data.repository.DocumentRepository
import com.docscanner.ui.components.MatchMode
import com.docscanner.util.ZipUtil
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
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
    val showTagFilterSheet: Boolean = false,
    val filterTagIds: Set<Long> = emptySet(),
    val filterMatchMode: MatchMode = MatchMode.MATCH_ANY,
    val showShareSheet: Boolean = false,
    val shareFilteredDocs: Boolean = false,
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

    private val _filterTagIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _filterMatchMode = MutableStateFlow(MatchMode.MATCH_ANY)

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
            ) { docs, sortOrder, query, inContent, ocrIds -> listOf(docs, sortOrder, query, inContent, ocrIds) }
            .combine(
                combine(_filterTagIds, _filterMatchMode, _documentTagMap) { a, b, c -> listOf(a, b, c) }
            ) { main, filter ->
                val docs = main[0] as List<Document>
                val sortOrder = main[1] as SortOrder
                val query = main[2] as String
                val inContent = main[3] as Boolean
                val ocrIds = main[4] as Set<*>
                val filterIds = filter[0] as Set<*>
                val matchMode = filter[1] as MatchMode
                val tagMap = filter[2] as Map<*, *>

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
                val tagIds = filterIds as Set<Long>
                val tagFiltered = if (tagIds.isEmpty()) filtered
                else {
                    @Suppress("UNCHECKED_CAST")
                    val dtm = tagMap as Map<Long, List<Tag>>
                    filtered.filter { doc ->
                        val docTagIds = dtm[doc.id].orEmpty().map { it.id }.toSet()
                        if (matchMode == MatchMode.MATCH_ALL) tagIds.all { it in docTagIds }
                        else tagIds.any { it in docTagIds }
                    }
                }
                val sorted = when (sortOrder) {
                    SortOrder.MODIFIED_DESC -> tagFiltered.sortedByDescending { it.updatedAt }
                    SortOrder.CREATED_DESC -> tagFiltered.sortedByDescending { it.createdAt }
                    SortOrder.SIZE_DESC -> tagFiltered.sortedByDescending { it.totalFileSize }
                    SortOrder.NAME_ASC -> tagFiltered.sortedBy { it.name.lowercase() }
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

    fun showTagFilterSheet() {
        _uiState.update { it.copy(showTagFilterSheet = true) }
    }

    fun hideTagFilterSheet() {
        _uiState.update { it.copy(showTagFilterSheet = false) }
    }

    fun toggleFilterTag(tagId: Long) {
        val newSet = _uiState.value.filterTagIds.let { ids ->
            if (tagId in ids) ids - tagId else ids + tagId
        }
        _filterTagIds.value = newSet
        _uiState.update { it.copy(filterTagIds = newSet) }
    }

    fun setFilterMatchMode(mode: MatchMode) {
        _filterMatchMode.value = mode
        _uiState.update { it.copy(filterMatchMode = mode) }
    }

    fun showShareSheet() {
        _uiState.update { it.copy(showShareSheet = true, shareFilteredDocs = false) }
    }

    fun showFilteredShareSheet() {
        _uiState.update { it.copy(showShareSheet = true, shareFilteredDocs = true) }
    }

    fun hideShareSheet() {
        _uiState.update { it.copy(showShareSheet = false, shareFilteredDocs = false) }
    }

    fun shareViaSystem(context: Context) {
        val docs = getDocsToShare()
        if (docs.isEmpty()) return
        val contentResolver = context.contentResolver

        if (docs.size == 1) {
            shareSingleDoc(context, docs.first())
        } else {
            shareMultipleDocs(context, docs, contentResolver)
        }
        if (!_uiState.value.shareFilteredDocs) exitSelectionMode()
    }

    fun saveToDrive(context: Context, folderUri: Uri) {
        val docs = getDocsToShare()
        if (docs.isEmpty()) return
        val app = context.applicationContext

        if (docs.size == 1) {
            copyToDrive(app, folderUri, docs.first())
        } else {
            copyMultipleToDrive(app, folderUri, docs)
        }
        if (!_uiState.value.shareFilteredDocs) exitSelectionMode()
    }

    private fun getDocsToShare(): List<Document> {
        val state = _uiState.value
        val ids = if (state.shareFilteredDocs) {
            state.documents.map { it.id }
        } else {
            state.selectedDocumentIds.toList()
        }
        return ids.mapNotNull { id ->
            state.documents.find { it.id == id }
        }.filter { it.outputUri != null }
    }

    private fun shareableUri(context: Context, uriStr: String): Uri {
        val uri = Uri.parse(uriStr)
        if (uri.scheme == "file") {
            val file = File(uri.path!!)
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        return uri
    }

    private fun shareSingleDoc(context: Context, doc: Document) {
        val uri = shareableUri(context, doc.outputUri!!)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share PDF"))
    }

    private fun shareMultipleDocs(context: Context, docs: List<Document>, contentResolver: ContentResolver) {
        val baseName = zipBaseName()
        val files = docs.map { doc ->
            val name = doc.name.replace(" ", "_") + ".pdf"
            val uri = shareableUri(context, doc.outputUri!!)
            name to uri
        }
        val cacheDir = context.cacheDir
        val zipFile = ZipUtil.create(cacheDir, baseName, files, contentResolver)
        val zipUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, zipUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ZIP"))
        zipFile.delete()
    }

    private fun copyToDrive(app: Context, folderUri: Uri, doc: Document) {
        try {
            val folder = DocumentFile.fromTreeUri(app, folderUri)
            if (folder == null || !folder.exists()) return
            val sourceUri = shareableUri(app, doc.outputUri!!)
            val safeName = doc.name.replace(" ", "_").replace("/", "_") + ".pdf"
            val existing = folder.findFile(safeName)
            if (existing != null) existing.delete()
            val newFile = folder.createFile("application/pdf", safeName) ?: return
            app.contentResolver.openInputStream(sourceUri)?.use { input ->
                app.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
        } catch (_: Exception) { }
    }

    private fun copyMultipleToDrive(app: Context, folderUri: Uri, docs: List<Document>) {
        try {
            val parent = DocumentFile.fromTreeUri(app, folderUri) ?: return
            val dirName = zipBaseName()
            val dir = parent.createDirectory(dirName) ?: return
            for (doc in docs) {
                val sourceUri = shareableUri(app, doc.outputUri!!)
                val safeName = doc.name.replace(" ", "_").replace("/", "_") + ".pdf"
                val newFile = dir.createFile("application/pdf", safeName) ?: continue
                app.contentResolver.openInputStream(sourceUri)?.use { input ->
                    app.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun zipBaseName(): String {
        val filterIds = _uiState.value.filterTagIds
        if (filterIds.isNotEmpty()) {
            val names = filterIds.mapNotNull { id ->
                allTags.value.find { it.id == id }?.name?.replace(" ", "_")
            }
            if (names.isNotEmpty()) {
                val delimiter = if (_uiState.value.filterMatchMode == MatchMode.MATCH_ANY) "+" else "-"
                return names.joinToString(delimiter)
            }
        }
        val now = LocalDateTime.now()
        return now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    }
}
