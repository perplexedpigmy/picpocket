package com.docscanner.domain.filter

import android.graphics.Color
import com.docscanner.util.TestBitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrightnessFilterTest {

    private val filter = BrightnessFilter()

    @Test
    fun `white stays white (clamped)`() {
        val bitmap = TestBitmapFactory.allWhite()
        val result = filter.apply(bitmap)
        assertEquals(Color.WHITE, result.getPixel(0, 0))
    }

    @Test
    fun `brightness filter runs without error`() {
        val bitmap = TestBitmapFactory.gradient()
        val result = filter.apply(bitmap)
        assertEquals(bitmap.width, result.width)
        assertEquals(bitmap.height, result.height)
    }
}
