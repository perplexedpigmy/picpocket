package com.picpocket.app.domain.scanner

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
open class ScannerManager @Inject constructor() {

    private var scanner: GmsDocumentScanner? = null

    open fun createOptions(): GmsDocumentScannerOptions {
        return GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }

    open suspend fun getStartScanIntentSender(activity: Activity): IntentSender {
        Log.d("ScannerManager", "getStartScanIntentSender called")
        val opts = createOptions()
        Log.d("ScannerManager", "options created")
        scanner = GmsDocumentScanning.getClient(opts)
        Log.d("ScannerManager", "scanner client created: $scanner")
        return suspendCancellableCoroutine { continuation ->
            Log.d("ScannerManager", "calling getStartScanIntent")
            scanner!!.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    Log.d("ScannerManager", "getStartScanIntent succeeded: $intentSender")
                    continuation.resume(intentSender)
                }
                .addOnFailureListener { exception ->
                    Log.e("ScannerManager", "getStartScanIntent failed", exception)
                    continuation.resumeWith(Result.failure(exception))
                }
        }
    }

    open suspend fun handleResult(data: Intent?): ScannerResult {
        return suspendCancellableCoroutine { continuation ->
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data)
            if (scanningResult == null) {
                continuation.resume(ScannerResult.Cancelled)
                return@suspendCancellableCoroutine
            }

            val pages = scanningResult.pages
            if (pages.isNullOrEmpty()) {
                continuation.resume(ScannerResult.Cancelled)
                return@suspendCancellableCoroutine
            }

            try {
                val imageUris = pages.map { it.imageUri }
                continuation.resume(ScannerResult.MultiplePagesCaptured(imageUris))
            } catch (e: Exception) {
                continuation.resume(ScannerResult.Error(e))
            }
        }
    }
}
