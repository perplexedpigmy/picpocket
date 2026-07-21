package com.picpocket.app.domain.ocr

import android.graphics.BitmapFactory
import com.picpocket.app.data.store.DocumentStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OcrManager @Inject constructor(
    private val ocrEngine: OcrEngine,
    private val store: DocumentStore,
) {

    open suspend fun runOcr(documentId: String) {
        val doc = store.readMetadata(documentId).getOrNull() ?: return
        if (doc.ocrComplete) return
        val pending = doc.pages.filter { it.ocrText == null }
        if (pending.isEmpty()) return
        for (page in pending) {
            val pageFile = store.pageFile(documentId, page.filename)
            val bitmap = BitmapFactory.decodeFile(pageFile.absolutePath) ?: continue
            val result = ocrEngine.recognize(bitmap)
            store.updatePageOcrText(documentId, page.pageNumber, result.text)
        }
        val updated = store.readMetadata(documentId).getOrNull() ?: return
        val allDone = updated.pages.all { it.ocrText != null }
        if (allDone) {
            store.writeMetadata(documentId, updated.copy(ocrComplete = true))
        }
    }
}
