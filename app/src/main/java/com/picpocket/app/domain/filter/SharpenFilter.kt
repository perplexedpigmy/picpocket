package com.picpocket.app.domain.filter

import android.graphics.Bitmap
import android.graphics.Color
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharpenFilter @Inject constructor() : ImageFilter {

    override fun apply(input: Bitmap): Bitmap {
        val width = input.width
        val height = input.height
        val output = input.copy(input.config, true)
        val srcPixels = IntArray(width * height)
        input.getPixels(srcPixels, 0, width, 0, 0, width, height)

        val kernel = floatArrayOf(
            0f, -1f, 0f,
            -1f, 5f, -1f,
            0f, -1f, 0f,
        )

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var r = 0f
                var g = 0f
                var b = 0f
                var ki = 0
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixel = srcPixels[(y + ky) * width + (x + kx)]
                        r += Color.red(pixel) * kernel[ki]
                        g += Color.green(pixel) * kernel[ki]
                        b += Color.blue(pixel) * kernel[ki]
                        ki++
                    }
                }
                output.setPixel(
                    x, y,
                    Color.rgb(
                        r.coerceIn(0f, 255f).toInt(),
                        g.coerceIn(0f, 255f).toInt(),
                        b.coerceIn(0f, 255f).toInt(),
                    )
                )
            }
        }
        return output
    }
}
