package com.picpocket.app.domain.export

sealed interface PdfResult {
    data class Success(val uri: String) : PdfResult
    data class Error(val exception: Throwable) : PdfResult
}
