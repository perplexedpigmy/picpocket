package com.docscanner.domain.filter

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FilterPipeline @Inject constructor(
    private val grayscaleFilter: GrayscaleFilter,
    private val contrastFilter: ContrastFilter,
    private val brightnessFilter: BrightnessFilter,
    private val sharpenFilter: SharpenFilter,
    private val binarizeFilter: BinarizeFilter,
) {

    fun apply(filters: List<FilterType>, input: Bitmap): Bitmap {
        var bitmap = input
        for (filter in filters) {
            if (filter == FilterType.NONE) continue
            bitmap = getFilter(filter).apply(bitmap)
        }
        return bitmap
    }

    fun getFilter(type: FilterType): ImageFilter {
        return when (type) {
            FilterType.GRAYSCALE -> grayscaleFilter
            FilterType.CONTRAST -> contrastFilter
            FilterType.BRIGHTEN -> brightnessFilter
            FilterType.SHARPEN -> sharpenFilter
            FilterType.BINARIZE -> binarizeFilter
            FilterType.NONE -> NoOpFilter
        }
    }

    private data object NoOpFilter : ImageFilter {
        override fun apply(input: Bitmap): Bitmap = input
    }
}
