package com.docscanner.domain.pdf

sealed interface PdfResult {
    data class Success(val uri: String) : PdfResult
    data class Error(val exception: Throwable) : PdfResult
}
