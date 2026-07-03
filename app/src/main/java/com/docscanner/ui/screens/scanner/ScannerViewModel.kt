package com.docscanner.ui.screens.scanner

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.data.repository.DocumentRepository
import com.docscanner.di.SearchablePdf
import com.docscanner.domain.filter.FilterPipeline
import com.docscanner.domain.filter.FilterType
import com.docscanner.domain.ocr.OcrEngine
import com.docscanner.domain.pdf.PdfGenerator
import com.docscanner.domain.pdf.PdfResult
import com.docscanner.domain.scanner.ScannerManager
import com.docscanner.domain.scanner.ScannerResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class CapturedPage(
    val imageUri: Uri,
    val ocrText: String? = null,
    val filterTypes: List<FilterType> = emptyList(),
)

data class ScannerUiState(
    val capturedPages: List<CapturedPage> = emptyList(),
    val currentPageIndex: Int = -1,
    val isScanning: Boolean = false,
    val isSaving: Boolean = false,
    val documentName: String = "",
    val showNameDialog: Boolean = false,
    val showFilterSheet: Boolean = false,
    val scanError: String? = null,
    val saveError: String? = null,
    val savedDocumentId: Long? = null,
    val pendingIntentSender: IntentSender? = null,
)

@HiltViewModel
class ScannerViewModel @Inject constructor(
    application: Application,
    private val repository: DocumentRepository,
    private val scannerManager: ScannerManager,
    private val filterPipeline: FilterPipeline,
    private val ocrEngine: OcrEngine,
    @SearchablePdf private val searchablePdf: PdfGenerator,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    init {
        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")
        _uiState.update { it.copy(documentName = "Scan_${now.format(formatter)}") }
    }

    fun getScanIntentSender(activity: Activity) {
        Log.d("ScannerViewModel", "getScanIntentSender called, activity=$activity")
        viewModelScope.launch {
            try {
                Log.d("ScannerViewModel", "starting scan, isScanning=true")
                _uiState.update { it.copy(isScanning = true, scanError = null) }
                val intentSender = scannerManager.getStartScanIntentSender(activity)
                Log.d("ScannerViewModel", "got intentSender=$intentSender")
                _uiState.update { it.copy(pendingIntentSender = intentSender, isScanning = false) }
            } catch (e: Exception) {
                Log.e("ScannerViewModel", "getScanIntentSender failed", e)
                _uiState.update { it.copy(isScanning = false, scanError = e.message) }
            }
        }
    }

    fun clearPendingIntentSender() {
        Log.d("ScannerViewModel", "clearPendingIntentSender")
        _uiState.update { it.copy(pendingIntentSender = null) }
    }

    fun onScannerResult(result: ScannerResult) {
        Log.d("ScannerViewModel", "onScannerResult: $result")
        when (result) {
            is ScannerResult.PageCaptured -> {
                val page = CapturedPage(imageUri = result.imageUri)
                _uiState.update { state ->
                    state.copy(
                        capturedPages = state.capturedPages + page,
                        currentPageIndex = state.capturedPages.size,
                    )
                }
            }
            is ScannerResult.Cancelled -> {
                Log.d("ScannerViewModel", "scanner cancelled")
            }
            is ScannerResult.Error -> {
                Log.e("ScannerViewModel", "scanner error", result.exception)
            }
        }
    }

    fun handleScannerIntent(data: Intent?) {
        Log.d("ScannerViewModel", "handleScannerIntent: data=$data")
        viewModelScope.launch {
            val result = scannerManager.handleResult(data)
            onScannerResult(result)
        }
    }

    fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            val app = getApplication<Application>()
            app.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun applyFilter(pageIndex: Int, filterType: FilterType) {
        _uiState.update { state ->
            val pages = state.capturedPages.toMutableList()
            val page = pages[pageIndex]
            val currentFilters = page.filterTypes.toMutableList()
            if (filterType in currentFilters) {
                currentFilters.remove(filterType)
            } else {
                currentFilters.add(filterType)
            }
            pages[pageIndex] = page.copy(filterTypes = currentFilters)
            state.copy(capturedPages = pages)
        }
    }

    fun showNameDialog() {
        _uiState.update { it.copy(showNameDialog = true) }
    }

    fun hideNameDialog() {
        _uiState.update { it.copy(showNameDialog = false) }
    }

    fun updateDocumentName(name: String) {
        _uiState.update { it.copy(documentName = name) }
    }

    fun showFilterSheet(pageIndex: Int) {
        _uiState.update { it.copy(showFilterSheet = true, currentPageIndex = pageIndex) }
    }

    fun hideFilterSheet() {
        _uiState.update { it.copy(showFilterSheet = false) }
    }

    fun removePage(index: Int) {
        _uiState.update { state ->
            val pages = state.capturedPages.toMutableList()
            pages.removeAt(index)
            state.copy(capturedPages = pages)
        }
    }

    fun saveDocument(outputUri: Uri) {
        val state = _uiState.value
        if (state.documentName.isBlank() || state.capturedPages.isEmpty()) return

        _uiState.update { it.copy(isSaving = true, saveError = null) }

        viewModelScope.launch {
            try {
                val documentId = repository.createDocument(state.documentName)
                val app = getApplication<Application>()
                val pagesDir = File(app.cacheDir, "pages/$documentId")
                pagesDir.mkdirs()

                for ((i, captured) in state.capturedPages.withIndex()) {
                    val bitmap = app.contentResolver.openInputStream(captured.imageUri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    } ?: continue

                    val pageFile = File(pagesDir, "page_$i.jpg")
                    withContext(Dispatchers.IO) {
                        FileOutputStream(pageFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                    }
                    val imageUri = Uri.fromFile(pageFile).toString()
                    val pageId = repository.addPage(documentId, imageUri, fileSizeBytes = pageFile.length())

                    val filteredBitmap = filterPipeline.apply(captured.filterTypes, bitmap)
                    val ocrResult = ocrEngine.recognize(filteredBitmap)
                    repository.updatePageOcrText(pageId, ocrResult.text)
                    bitmap.recycle()
                    filteredBitmap.recycle()
                }

                val pages = repository.getPages(documentId)
                val pdfResult = searchablePdf.generate(app, pages, outputUri)
                when (pdfResult) {
                    is PdfResult.Success -> {
                        repository.updateDocumentOutputUri(documentId, pdfResult.uri)
                        _uiState.update { it.copy(isSaving = false, savedDocumentId = documentId) }
                    }
                    is PdfResult.Error -> {
                        _uiState.update { it.copy(isSaving = false, saveError = pdfResult.exception.message) }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, saveError = e.message) }
            }
        }
    }
}
