package com.docscanner.di

import com.docscanner.domain.filter.BinarizeFilter
import com.docscanner.domain.filter.BrightnessFilter
import com.docscanner.domain.filter.ContrastFilter
import com.docscanner.domain.filter.FilterPipeline
import com.docscanner.domain.filter.GrayscaleFilter
import com.docscanner.domain.filter.SharpenFilter
import com.docscanner.domain.ocr.MlKitOcrEngine
import com.docscanner.domain.ocr.OcrEngine
import com.docscanner.domain.pdf.ImageOnlyPdfGenerator
import com.docscanner.domain.pdf.PdfGenerator
import com.docscanner.domain.pdf.SearchablePdfGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImageOnlyPdf

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SearchablePdf

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOcrEngine(): OcrEngine = MlKitOcrEngine()

    @Provides
    @Singleton
    fun provideFilterPipeline(
        grayscaleFilter: GrayscaleFilter,
        contrastFilter: ContrastFilter,
        brightnessFilter: BrightnessFilter,
        sharpenFilter: SharpenFilter,
        binarizeFilter: BinarizeFilter,
    ): FilterPipeline = FilterPipeline(
        grayscaleFilter, contrastFilter, brightnessFilter, sharpenFilter, binarizeFilter,
    )

    @Provides
    @Singleton
    @ImageOnlyPdf
    fun provideImageOnlyPdfGenerator(): PdfGenerator = ImageOnlyPdfGenerator()

    @Provides
    @Singleton
    @SearchablePdf
    fun provideSearchablePdfGenerator(): PdfGenerator = SearchablePdfGenerator()
}
