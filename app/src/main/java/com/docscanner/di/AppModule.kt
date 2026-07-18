package com.docscanner.di

import android.content.Context
import androidx.room.Room
import com.docscanner.data.local.DocScannerDatabase
import com.docscanner.data.local.dao.TagAutomationDao
import com.docscanner.data.local.dao.TagDao
import com.docscanner.data.repository.DocumentRepository
import com.docscanner.data.repository.DocumentRepositoryImpl
import com.docscanner.domain.filter.BinarizeFilter
import com.docscanner.domain.filter.BrightnessFilter
import com.docscanner.domain.filter.ContrastFilter
import com.docscanner.domain.filter.FilterPipeline
import com.docscanner.domain.filter.GrayscaleFilter
import com.docscanner.domain.filter.SharpenFilter
import com.docscanner.domain.ocr.MlKitOcrEngine
import com.docscanner.domain.ocr.OcrEngine
import com.docscanner.domain.export.ImageOnlyPdfGenerator
import com.docscanner.domain.export.PdfGenerator
import com.docscanner.domain.export.SearchablePdfGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
    fun provideDatabase(@ApplicationContext context: Context): DocScannerDatabase {
        return Room.databaseBuilder(
            context,
            DocScannerDatabase::class.java,
            "docscanner.db",
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideTagDao(database: DocScannerDatabase): TagDao = database.tagDao()

    @Provides
    fun provideTagAutomationDao(database: DocScannerDatabase): TagAutomationDao = database.tagAutomationDao()

    @Provides
    @Singleton
    fun provideDocumentRepository(impl: DocumentRepositoryImpl): DocumentRepository = impl

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
