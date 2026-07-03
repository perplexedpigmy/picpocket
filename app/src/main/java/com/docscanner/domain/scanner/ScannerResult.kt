package com.docscanner.domain.scanner

import android.net.Uri

sealed interface ScannerResult {
    data class PageCaptured(val imageUri: Uri) : ScannerResult
    data object Cancelled : ScannerResult
    data class Error(val exception: Throwable) : ScannerResult
}
