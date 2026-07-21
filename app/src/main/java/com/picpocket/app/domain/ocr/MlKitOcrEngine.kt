package com.picpocket.app.domain.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class MlKitOcrEngine @Inject constructor() : OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    continuation.resume(
                        OcrResult(
                            text = result.text,
                            confidence = 0.85f,
                        )
                    )
                }
                .addOnFailureListener { _ ->
                    continuation.resume(
                        OcrResult(text = "", confidence = 0f)
                    )
                }
        }
    }
}
