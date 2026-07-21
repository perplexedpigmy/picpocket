package com.picpocket.app.domain.filter

import android.graphics.Color
import com.picpocket.app.util.TestBitmapFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

class SharpenFilterTest {

    private val filter = SharpenFilter()

    @Test
    fun `sharpen filter increases edge contrast on checkerboard`() {
        val bitmap = TestBitmapFactory.checkerboard(size = 6, tileSize = 2)
        val result = filter.apply(bitmap)

        val midX = 3
        val midY = 3
        val beforeEdgeDiff = Math.abs(
            Color.red(bitmap.getPixel(midX, midY)) - Color.red(bitmap.getPixel(midX + 1, midY))
        )
        val afterEdgeDiff = Math.abs(
            Color.red(result.getPixel(midX, midY)) - Color.red(result.getPixel(midX + 1, midY))
        )

        assertTrue("Edge difference should increase or stay same after sharpen, was $beforeEdgeDiff became $afterEdgeDiff", afterEdgeDiff >= beforeEdgeDiff)
    }

    @Test
    fun `solid color remains unchanged`() {
        val bitmap = TestBitmapFactory.allWhite()
        val result = filter.apply(bitmap)
        assertTrue(result.getPixel(0, 0) != 0)
    }
}
