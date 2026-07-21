package com.picpocket.app.domain.filter

import android.graphics.Color
import com.picpocket.app.util.TestBitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

class FilterPipelineTest {

    private lateinit var pipeline: FilterPipeline

    @Before
    fun setUp() {
        pipeline = FilterPipeline(
            GrayscaleFilter(),
            ContrastFilter(),
            BrightnessFilter(),
            SharpenFilter(),
            BinarizeFilter(),
        )
    }

    @Test
    fun `empty filter list returns same bitmap`() {
        val bitmap = TestBitmapFactory.gradient()
        val result = pipeline.apply(emptyList(), bitmap)
        assertEquals(bitmap.getPixel(0, 0), result.getPixel(0, 0))
    }

    @Test
    fun `NONE filter returns same bitmap`() {
        val bitmap = TestBitmapFactory.gradient()
        val result = pipeline.apply(listOf(FilterType.NONE), bitmap)
        assertEquals(bitmap.getPixel(0, 0), result.getPixel(0, 0))
    }

    @Test
    fun `grayscale then binarize produces black and white only`() {
        val bitmap = TestBitmapFactory.threeColorStripe()
        val result = pipeline.apply(listOf(FilterType.GRAYSCALE, FilterType.BINARIZE), bitmap)

        for (x in 0 until result.width) {
            val gray = Color.red(result.getPixel(x, 0))
            assertTrue("Pixel $x is $gray, expected 0 or 255", gray == 0 || gray == 255)
        }
    }

    @Test
    fun `multiple filters chain correctly without crashing`() {
        val bitmap = TestBitmapFactory.gradient(20, 20)
        val result = pipeline.apply(
            listOf(FilterType.GRAYSCALE, FilterType.CONTRAST, FilterType.BRIGHTEN, FilterType.SHARPEN),
            bitmap,
        )
        assertEquals(20, result.width)
        assertEquals(20, result.height)
    }
}
