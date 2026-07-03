package com.docscanner.util

import android.graphics.Bitmap
import android.graphics.Color

object TestBitmapFactory {

    fun allWhite(width: Int = 10, height: Int = 10): Bitmap {
        return solidColor(Color.WHITE, width, height)
    }

    fun allBlack(width: Int = 10, height: Int = 10): Bitmap {
        return solidColor(Color.BLACK, width, height)
    }

    fun solidColor(color: Int, width: Int = 10, height: Int = 10): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    fun gradient(width: Int = 10, height: Int = 10): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val gray = ((x.toFloat() / width + y.toFloat() / height) / 2f * 255f).toInt()
                bitmap.setPixel(x, y, Color.rgb(gray, gray, gray))
            }
        }
        return bitmap
    }

    fun checkerboard(size: Int = 10, tileSize: Int = 2): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val on = ((x / tileSize) + (y / tileSize)) % 2 == 0
                bitmap.setPixel(x, y, if (on) Color.WHITE else Color.BLACK)
            }
        }
        return bitmap
    }

    fun threeColorStripe(): Bitmap {
        val bitmap = Bitmap.createBitmap(12, 1, Bitmap.Config.ARGB_8888)
        for (x in 0 until 4) bitmap.setPixel(x, 0, Color.RED)
        for (x in 4 until 8) bitmap.setPixel(x, 0, Color.GREEN)
        for (x in 8 until 12) bitmap.setPixel(x, 0, Color.BLUE)
        return bitmap
    }
}
