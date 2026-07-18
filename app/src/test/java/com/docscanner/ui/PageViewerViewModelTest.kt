package com.docscanner.ui

import androidx.test.core.app.ApplicationProvider
import com.docscanner.data.FakeDocumentRepository
import com.docscanner.data.model.DocumentId
import com.docscanner.ui.screens.viewer.PageViewerViewModel
import com.docscanner.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class PageViewerViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var repo: FakeDocumentRepository
    private lateinit var viewModel: PageViewerViewModel
    private var documentId: DocumentId = ""

    @Before
    fun setUp() = runTest {
        repo = FakeDocumentRepository()
        documentId = repo.createDocument("Viewer Test")
        repo.addPage(documentId, "content://page1.jpg")
        repo.addPage(documentId, "content://page2.jpg")
        repo.addPage(documentId, "content://page3.jpg")
        viewModel = PageViewerViewModel(ApplicationProvider.getApplicationContext<android.app.Application>(), repo)
    }

    @Test
    fun `initial state has empty pages and isLoading`() {
        val state = viewModel.uiState.value
        assertTrue(state.pages.isEmpty())
        assertTrue(state.isLoading)
    }

    @Test
    fun `loadPages populates pages`() = runTest {
        viewModel.loadPages(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(3, state.pages.size)
    }

    @Test
    fun `setPageIndex updates currentIndex`() {
        viewModel.setPageIndex(2)
        assertEquals(2, viewModel.uiState.value.currentIndex)

        viewModel.setPageIndex(0)
        assertEquals(0, viewModel.uiState.value.currentIndex)
    }
}
