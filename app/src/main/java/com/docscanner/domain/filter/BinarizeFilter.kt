package com.docscanner.domain.filter

import android.graphics.Bitmap
import android.graphics.Color
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinarizeFilter @Inject constructor() : ImageFilter {

    override fun apply(input: Bitmap): Bitmap {
        val width = input.width
        val height = input.height
        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)

        var sum = 0L
        for (pixel in pixels) {
            sum += (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
        }
        val threshold = (sum / pixels.size).toInt().coerceIn(1, 254)

        for (i in pixels.indices) {
            val gray = (Color.red(pixels[i]) + Color.green(pixels[i]) + Color.blue(pixels[i])) / 3
            val value = if (gray >= threshold) 255 else 0
            pixels[i] = Color.rgb(value, value, value)
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }
}
