package com.picpocket.app.domain.ocr

import android.graphics.Bitmap

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrResult
}
