package com.docscanner.domain.filter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrightnessFilter @Inject constructor() : ImageFilter {

    override fun apply(input: Bitmap): Bitmap {
        val output = input.copy(input.config, true)
        val canvas = Canvas(output)
        val brightness = 50f
        val matrix = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, brightness,
                0f, 1f, 0f, 0f, brightness,
                0f, 0f, 1f, 0f, brightness,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(input, 0f, 0f, paint)
        return output
    }
}
