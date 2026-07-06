package com.docscanner.ui

import com.docscanner.data.FakeDocumentRepository
import com.docscanner.ui.screens.home.HomeViewModel
import com.docscanner.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class HomeViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var repo: FakeDocumentRepository
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        repo = FakeDocumentRepository()
        viewModel = HomeViewModel(repo)
    }

    @Test
    fun `initial state has loading true`() {
        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `loads documents from repository`() = runTest {
        repo.createDocument("Doc1")
        repo.createDocument("Doc2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.documents.size)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `sort by name orders alphabetically`() = runTest {
        repo.createDocument("Beta")
        repo.createDocument("Alpha")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSortOrder(com.docscanner.ui.screens.home.SortOrder.NAME_ASC)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Alpha", viewModel.uiState.value.documents[0].name)
        assertEquals("Beta", viewModel.uiState.value.documents[1].name)
    }

    @Test
    fun `sort by modified orders descending`() = runTest {
        val id1 = repo.createDocument("Doc1")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        Thread.sleep(10)
        val id2 = repo.createDocument("Doc2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(id2, viewModel.uiState.value.documents[0].id)
        assertEquals(id1, viewModel.uiState.value.documents[1].id)
    }

    @Test
    fun `toggle selection mode`() {
        assertFalse(viewModel.uiState.value.selectionMode)
        viewModel.onDocumentLongPress(1L)
        assertTrue(viewModel.uiState.value.selectionMode)
        assertEquals(setOf(1L), viewModel.uiState.value.selectedDocumentIds)
    }

    @Test
    fun `tap in selection mode toggles without navigating`() = runTest {
        repo.createDocument("Doc1")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val docId = viewModel.uiState.value.documents[0].id
        viewModel.onDocumentLongPress(docId)
        assertTrue(viewModel.uiState.value.selectionMode)

        val shouldNavigate = viewModel.onDocumentTap(docId)
        assertFalse(shouldNavigate)
        assertTrue(viewModel.uiState.value.selectedDocumentIds.isEmpty())
    }

    @Test
    fun `select all selects all documents`() = runTest {
        repo.createDocument("Doc1")
        repo.createDocument("Doc2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.onDocumentLongPress(viewModel.uiState.value.documents[0].id)
        viewModel.selectAll()
        assertEquals(2, viewModel.uiState.value.selectedDocumentIds.size)
    }

    @Test
    fun `deselect all clears selection`() = runTest {
        repo.createDocument("Doc1")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.onDocumentLongPress(viewModel.uiState.value.documents[0].id)
        viewModel.deselectAll()
        assertTrue(viewModel.uiState.value.selectedDocumentIds.isEmpty())
    }

    @Test
    fun `exit selection mode`() = runTest {
        repo.createDocument("Doc1")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.onDocumentLongPress(viewModel.uiState.value.documents[0].id)
        viewModel.exitSelectionMode()
        assertFalse(viewModel.uiState.value.selectionMode)
    }

    @Test
    fun `delete selected documents`() = runTest {
        repo.createDocument("Doc1")
        repo.createDocument("Doc2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.documents.size)

        viewModel.onDocumentLongPress(viewModel.uiState.value.documents[0].id)
        viewModel.selectAll()
        viewModel.showDeleteConfirmation()
        assertTrue(viewModel.uiState.value.showDeleteConfirmation)

        viewModel.deleteSelected()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.documents.size)
    }

    @Test
    fun `search query filters by name`() = runTest {
        repo.createDocument("Tax Return 2024")
        repo.createDocument("Medical Report")
        repo.createDocument("Tax Notes")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("Tax")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.documents.size)
        assertTrue(viewModel.uiState.value.documents.all { it.name.startsWith("Tax") })
    }

    @Test
    fun `search query is case insensitive`() = runTest {
        repo.createDocument("Invoice")
        repo.createDocument("Receipt")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("INVOICE")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.documents.size)
        assertEquals("Invoice", viewModel.uiState.value.documents[0].name)
    }

    @Test
    fun `search with regex pattern`() = runTest {
        repo.createDocument("Report Q1 2024")
        repo.createDocument("Report Q2 2024")
        repo.createDocument("Notes")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("Report Q[12]")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.documents.size)
    }

    @Test
    fun `clear search shows all documents`() = runTest {
        repo.createDocument("Alpha")
        repo.createDocument("Beta")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("Alpha")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.documents.size)

        viewModel.setSearchQuery("")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.documents.size)
    }

    @Test
    fun `search in content finds documents by OCR text`() = runTest {
        val doc1 = repo.createDocument("Invoice")
        val doc2 = repo.createDocument("Receipt")
        val page1 = repo.addPage(doc1, "content://page1.jpg")
        val page2 = repo.addPage(doc2, "content://page2.jpg")
        repo.updatePageOcrText(page1, "This invoice is for $500")
        repo.updatePageOcrText(page2, "Receipt for groceries")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleSearchInContent()
        viewModel.setSearchQuery("invoice")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.documents.size)
        assertEquals("Invoice", viewModel.uiState.value.documents[0].name)
    }

    @Test
    fun `search in content matches both name and OCR`() = runTest {
        val doc1 = repo.createDocument("Tax Return")
        val doc2 = repo.createDocument("Notes")
        val page1 = repo.addPage(doc1, "content://page1.jpg")
        val page2 = repo.addPage(doc2, "content://page2.jpg")
        repo.updatePageOcrText(page2, "This is about tax deductions")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleSearchInContent()
        viewModel.setSearchQuery("tax")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.documents.size)
    }

    @Test
    fun `search in content only matches when toggled on`() = runTest {
        val doc1 = repo.createDocument("Invoice Summary")
        val page1 = repo.addPage(doc1, "content://page1.jpg")
        repo.updatePageOcrText(page1, "Total due: $1000")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("due")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.documents.size)
    }

    @Test
    fun `search query in ui state tracks current query`() = runTest {
        viewModel.setSearchQuery("test query")
        assertEquals("test query", viewModel.uiState.value.searchQuery)
    }
}
