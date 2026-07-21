package com.picpocket.app.domain.filter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContrastFilter @Inject constructor() : ImageFilter {

    override fun apply(input: Bitmap): Bitmap {
        val output = input.copy(input.config, true)
        val canvas = Canvas(output)
        val scale = 1.5f
        val translate = -64f
        val matrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(input, 0f, 0f, paint)
        return output
    }
}
