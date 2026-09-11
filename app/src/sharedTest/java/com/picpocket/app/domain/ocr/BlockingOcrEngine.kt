package com.picpocket.app.domain.ocr

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred

class BlockingOcrEngine : OcrEngine {

    val gate = CompletableDeferred<Unit>()

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        gate.await()
        return OcrResult(text = "released text", confidence = 0.9f)
    }
}