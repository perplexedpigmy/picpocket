package com.picpocket.app.domain.filter

import android.graphics.Color
import com.picpocket.app.util.TestBitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContrastFilterTest {

    private val filter = ContrastFilter()

    @Test
    fun `white stays white under contrast`() {
        val bitmap = TestBitmapFactory.allWhite()
        val result = filter.apply(bitmap)
        assertEquals(Color.WHITE, result.getPixel(0, 0))
    }

    @Test
    fun `black stays black under contrast`() {
        val bitmap = TestBitmapFactory.allBlack()
        val result = filter.apply(bitmap)
        assertEquals(Color.BLACK, result.getPixel(0, 0))
    }

    @Test
    fun `contrast filter runs without error`() {
        val bitmap = TestBitmapFactory.gradient()
        val result = filter.apply(bitmap)
        assertEquals(bitmap.width, result.width)
        assertEquals(bitmap.height, result.height)
    }
}
