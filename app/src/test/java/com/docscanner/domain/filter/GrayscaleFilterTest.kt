package com.docscanner.domain.filter

import android.graphics.Color
import com.docscanner.util.TestBitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GrayscaleFilterTest {

    private val filter = GrayscaleFilter()

    @Test
    fun `applying grayscale returns bitmap of same dimensions`() {
        val bitmap = TestBitmapFactory.threeColorStripe()
        val result = filter.apply(bitmap)
        assertEquals(bitmap.width, result.width)
        assertEquals(bitmap.height, result.height)
        assertNotNull(result)
    }

    @Test
    fun `applying grayscale to all-white bitmap remains white`() {
        val bitmap = TestBitmapFactory.allWhite()
        val result = filter.apply(bitmap)
        assertEquals(Color.WHITE, result.getPixel(0, 0))
    }

    @Test
    fun `applying grayscale to all-black bitmap remains black`() {
        val bitmap = TestBitmapFactory.allBlack()
        val result = filter.apply(bitmap)
        assertEquals(Color.BLACK, result.getPixel(0, 0))
    }

    @Test
    fun `applying grayscale runs without error on gradient`() {
        val bitmap = TestBitmapFactory.gradient()
        val result = filter.apply(bitmap)
        assertEquals(bitmap.width, result.width)
        assertEquals(bitmap.height, result.height)
    }
}
