package com.docscanner.ui

import com.docscanner.data.FakeDocumentRepository
import com.docscanner.ui.screens.detail.DocumentDetailViewModel
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

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DocumentDetailViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var repo: FakeDocumentRepository
    private lateinit var viewModel: DocumentDetailViewModel
    private var documentId: Long = 0

    @Before
    fun setUp() = runTest {
        repo = FakeDocumentRepository()
        documentId = repo.createDocument("My Document")
        repo.addPage(documentId, "content://page1.jpg")
        repo.addPage(documentId, "content://page2.jpg")
        viewModel = DocumentDetailViewModel(repo)
    }

    @Test
    fun `show and hide rename dialog with loaded document`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.showRenameDialog()
        assertTrue(viewModel.uiState.value.showRenameDialog)
        assertEquals("My Document", viewModel.uiState.value.renameText)

        viewModel.hideRenameDialog()
        assertFalse(viewModel.uiState.value.showRenameDialog)
    }

    @Test
    fun `rename document updates name`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.showRenameDialog()
        viewModel.updateRenameText("Renamed Document")
        viewModel.renameDocument()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val doc = repo.getDocument(documentId)
        assertEquals("Renamed Document", doc?.name)
        assertFalse(viewModel.uiState.value.showRenameDialog)
    }

    @Test
    fun `update rename text`() {
        viewModel.updateRenameText("New Name")
        assertEquals("New Name", viewModel.uiState.value.renameText)
    }

    @Test
    fun `toggle edit mode`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isEditMode)
        viewModel.toggleEditMode()
        assertTrue(viewModel.uiState.value.isEditMode)
        viewModel.toggleEditMode()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isEditMode)
    }

    @Test
    fun `delete page removes it from pages list`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val pagesBefore = viewModel.uiState.value.pages
        assertEquals(2, pagesBefore.size)

        viewModel.deletePage(pagesBefore[0].id)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.pages.size)
    }

    @Test
    fun `showDeleteConfirmation toggles dialog`() {
        assertFalse(viewModel.uiState.value.showDeleteConfirmation)
        viewModel.showDeleteConfirmation()
        assertTrue(viewModel.uiState.value.showDeleteConfirmation)
        viewModel.dismissDeleteConfirmation()
        assertFalse(viewModel.uiState.value.showDeleteConfirmation)
    }

    @Test
    fun `deleteDocument removes document from state`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.document)

        viewModel.deleteDocument()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.document)
    }
}
