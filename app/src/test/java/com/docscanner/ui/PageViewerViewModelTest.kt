package com.docscanner.ui

import com.docscanner.data.FakeDocumentRepository
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
    private var documentId: Long = 0

    @Before
    fun setUp() = runTest {
        repo = FakeDocumentRepository()
        documentId = repo.createDocument("Viewer Test")
        repo.addPage(documentId, "content://page1.jpg")
        repo.addPage(documentId, "content://page2.jpg")
        repo.addPage(documentId, "content://page3.jpg")
        viewModel = PageViewerViewModel(repo)
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
        assertEquals("content://page1.jpg", state.pages[0].imageUri)
        assertEquals("content://page2.jpg", state.pages[1].imageUri)
        assertEquals("content://page3.jpg", state.pages[2].imageUri)
    }

    @Test
    fun `setPageIndex updates currentIndex`() {
        viewModel.setPageIndex(2)
        assertEquals(2, viewModel.uiState.value.currentIndex)

        viewModel.setPageIndex(0)
        assertEquals(0, viewModel.uiState.value.currentIndex)
    }
}
