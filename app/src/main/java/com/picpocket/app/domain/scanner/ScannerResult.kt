package com.picpocket.app.domain.scanner

import android.net.Uri

sealed interface ScannerResult {
    data class PageCaptured(val imageUri: Uri) : ScannerResult
    data class MultiplePagesCaptured(val imageUris: List<Uri>) : ScannerResult
    data object Cancelled : ScannerResult
    data class Error(val exception: Throwable) : ScannerResult
}
