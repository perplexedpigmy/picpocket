package com.picpocket.app.domain.filter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GrayscaleFilter @Inject constructor() : ImageFilter {

    override fun apply(input: Bitmap): Bitmap {
        val output = input.copy(input.config, true)
        val canvas = Canvas(output)
        val matrix = ColorMatrix(
            floatArrayOf(
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(input, 0f, 0f, paint)
        return output
    }
}
