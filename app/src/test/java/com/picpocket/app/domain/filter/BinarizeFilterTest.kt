package com.picpocket.app.domain.filter

import android.graphics.Color
import com.picpocket.app.util.TestBitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

class BinarizeFilterTest {

    private val filter = BinarizeFilter()

    @Test
    fun `binarize filter makes every pixel 0 or 255`() {
        val bitmap = TestBitmapFactory.gradient()
        val result = filter.apply(bitmap)

        for (y in 0 until result.height) {
            for (x in 0 until result.width) {
                val pixel = result.getPixel(x, y)
                val gray = Color.red(pixel)
                assertTrue("Pixel at ($x,$y) is $gray, expected 0 or 255", gray == 0 || gray == 255)
            }
        }
    }

    @Test
    fun `white stays white`() {
        val bitmap = TestBitmapFactory.allWhite()
        val result = filter.apply(bitmap)
        assertEquals(Color.WHITE, result.getPixel(0, 0))
    }

    @Test
    fun `black stays black`() {
        val bitmap = TestBitmapFactory.allBlack()
        val result = filter.apply(bitmap)
        assertEquals(Color.BLACK, result.getPixel(0, 0))
    }
}
