package com.docscanner.ui

import com.docscanner.data.FakeDocumentRepository
import com.docscanner.ui.screens.home.HomeViewModel
import com.docscanner.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `initial state is loading with no selection`() {
        val state = viewModel.uiState.value
        assertTrue(state.isLoading)
        assertFalse(state.selectionMode)
        assertTrue(state.selectedDocumentIds.isEmpty())
    }

    @Test
    fun `after documents loaded selection mode is off`() = runTest {
        repo.createDocument("Test")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("Should not be loading", state.isLoading)
        assertEquals(1, state.documents.size)
    }

    @Test
    fun `long press enters selection mode with that document`() = runTest {
        val id = repo.createDocument("Selectable")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.onDocumentLongPress(id)
        val state = viewModel.uiState.value
        assertTrue(state.selectionMode)
        assertEquals(setOf(id), state.selectedDocumentIds)
    }

    @Test
    fun `toggle selection adds and removes document`() = runTest {
        val id1 = repo.createDocument("A")
        val id2 = repo.createDocument("B")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.onDocumentLongPress(id1)
        viewModel.toggleSelection(id2)
        assertEquals(setOf(id1, id2), viewModel.uiState.value.selectedDocumentIds)

        viewModel.toggleSelection(id1)
        assertEquals(setOf(id2), viewModel.uiState.value.selectedDocumentIds)
    }

    @Test
    fun `exiting selection mode clears selection`() = runTest {
        val id = repo.createDocument("Doc")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.onDocumentLongPress(id)
        viewModel.exitSelectionMode()

        val state = viewModel.uiState.value
        assertFalse(state.selectionMode)
        assertTrue(state.selectedDocumentIds.isEmpty())
    }

    @Test
    fun `selectAll selects all documents`() = runTest {
        repo.createDocument("A")
        repo.createDocument("B")
        repo.createDocument("C")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals("docs should be loaded", 3, viewModel.uiState.value.documents.size)

        viewModel.onDocumentLongPress(1L)
        viewModel.selectAll()

        assertEquals(3, viewModel.uiState.value.selectedDocumentIds.size)
    }

    @Test
    fun `deleteSelected removes documents and exits selection`() = runTest {
        val id = repo.createDocument("To delete")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.onDocumentLongPress(id)
        viewModel.deleteSelected()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertNull(repo.getDocument(id))
        assertFalse(viewModel.uiState.value.selectionMode)
    }

    @Test
    fun `tapping during selection mode does not navigate`() {
        val shouldNavigate = viewModel.onDocumentTap(1L)
        assertTrue("Without selection mode, tap should navigate", shouldNavigate)

        viewModel.onDocumentLongPress(1L)
        val shouldNotNavigate = viewModel.onDocumentTap(1L)
        assertFalse("With selection mode, tap should not navigate", shouldNotNavigate)
    }
}
