package com.docscanner.domain.ocr

import android.graphics.Bitmap

class FakeOcrEngine : OcrEngine {

    var returnedText = "Fake OCR result text"
    var returnedConfidence = 0.95f

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        return OcrResult(text = returnedText, confidence = returnedConfidence)
    }
}
