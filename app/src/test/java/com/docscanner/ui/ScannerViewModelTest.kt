package com.docscanner.ui

import android.app.Application
import android.net.Uri
import com.docscanner.data.FakeDocumentRepository
import com.docscanner.domain.filter.BinarizeFilter
import com.docscanner.domain.filter.BrightnessFilter
import com.docscanner.domain.filter.ContrastFilter
import com.docscanner.domain.filter.FilterPipeline
import com.docscanner.domain.filter.FilterType
import com.docscanner.domain.filter.GrayscaleFilter
import com.docscanner.domain.filter.SharpenFilter
import com.docscanner.domain.ocr.FakeOcrEngine
import com.docscanner.domain.pdf.FakePdfGenerator
import com.docscanner.domain.scanner.ScannerResult
import com.docscanner.ui.screens.scanner.ScannerViewModel
import com.docscanner.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class ScannerViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var repo: FakeDocumentRepository
    private lateinit var fakeOcr: FakeOcrEngine
    private lateinit var fakePdf: FakePdfGenerator
    private lateinit var viewModel: ScannerViewModel

    @Before
    fun setUp() {
        repo = FakeDocumentRepository()
        fakeOcr = FakeOcrEngine()
        fakePdf = FakePdfGenerator()
        val pipeline = FilterPipeline(
            GrayscaleFilter(), ContrastFilter(), BrightnessFilter(),
            SharpenFilter(), BinarizeFilter(),
        )
        viewModel = ScannerViewModel(
            RuntimeEnvironment.getApplication() as Application,
            repo,
            com.docscanner.domain.scanner.ScannerManager(),
            pipeline,
            fakeOcr,
            fakePdf,
        )
    }

    @Test
    fun `initial state has no pages`() {
        val state = viewModel.uiState.value
        assertTrue(state.capturedPages.isEmpty())
        assertFalse(state.isSaving)
    }

    @Test
    fun `default document name starts with Scan_`() {
        val name = viewModel.uiState.value.documentName
        assertTrue("Default name should start with Scan_", name.startsWith("Scan_"))
    }

    @Test
    fun `default document name is not blank`() {
        assertFalse(viewModel.uiState.value.documentName.isBlank())
    }

    @Test
    fun `adding a page increments page count`() {
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://test/page.jpg"))
        )
        assertEquals(1, viewModel.uiState.value.capturedPages.size)
    }

    @Test
    fun `adding multiple pages increments correctly`() {
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page1.jpg"))
        )
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page2.jpg"))
        )
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page3.jpg"))
        )
        assertEquals(3, viewModel.uiState.value.capturedPages.size)
    }

    @Test
    fun `cancelled scanner adds no pages`() {
        viewModel.onScannerResult(ScannerResult.Cancelled)
        assertTrue(viewModel.uiState.value.capturedPages.isEmpty())
    }

    @Test
    fun `apply filter updates filterTypes on the page`() {
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page.jpg"))
        )
        viewModel.applyFilter(0, FilterType.GRAYSCALE)
        assertTrue(
            viewModel.uiState.value.capturedPages[0].filterTypes.contains(FilterType.GRAYSCALE)
        )
    }

    @Test
    fun `remove page decreases count`() {
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page1.jpg"))
        )
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page2.jpg"))
        )
        viewModel.removePage(0)
        assertEquals(1, viewModel.uiState.value.capturedPages.size)
    }

    @Test
    fun `show and hide name dialog`() {
        assertFalse(viewModel.uiState.value.showNameDialog)
        viewModel.showNameDialog()
        assertTrue(viewModel.uiState.value.showNameDialog)
        viewModel.hideNameDialog()
        assertFalse(viewModel.uiState.value.showNameDialog)
    }

    @Test
    fun `update document name`() {
        viewModel.updateDocumentName("My Document")
        assertEquals("My Document", viewModel.uiState.value.documentName)
    }

    @Test
    fun `save document with no name does nothing`() = runTest {
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page.jpg"))
        )
        viewModel.saveDocument(Uri.parse("file:///test/output.pdf"))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.savedDocumentId)
    }

    @Test
    fun `append mode sets isAppendMode flag`() {
        viewModel.setExistingDocumentId(123L)
        assertTrue(viewModel.uiState.value.isAppendMode)
    }

    @Test
    fun `append mode adds captured page to list`() = runTest {
        val docId = repo.createDocument("Append Test")
        repo.updateDocumentOutputUri(docId, "file:///test/output.pdf")
        viewModel.setExistingDocumentId(docId)

        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://test/page.jpg"))
        )

        assertEquals(1, viewModel.uiState.value.capturedPages.size)
        assertTrue(viewModel.uiState.value.isAppendMode)
    }

    @Test
    fun `show and hide filter sheet`() {
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page.jpg"))
        )
        viewModel.showFilterSheet(0)
        assertTrue(viewModel.uiState.value.showFilterSheet)
        assertEquals(0, viewModel.uiState.value.currentPageIndex)

        viewModel.hideFilterSheet()
        assertFalse(viewModel.uiState.value.showFilterSheet)
    }
}
