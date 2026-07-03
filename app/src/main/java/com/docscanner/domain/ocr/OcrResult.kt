package com.docscanner.domain.ocr

data class OcrResult(
    val text: String,
    val confidence: Float,
)
