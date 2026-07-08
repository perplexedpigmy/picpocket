package com.docscanner.ui

import com.docscanner.data.FakeDocumentRepository
import com.docscanner.ui.screens.tags.TagManagementViewModel
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
class TagManagementViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var repo: FakeDocumentRepository
    private lateinit var viewModel: TagManagementViewModel

    @Before
    fun setUp() {
        repo = FakeDocumentRepository()
        viewModel = TagManagementViewModel(repo)
    }

    @Test
    fun `initial state has empty tags`() {
        assertTrue(viewModel.uiState.value.allTags.isEmpty())
    }

    @Test
    fun `createTag adds to list`() = runTest {
        repo.createTag("Work")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.allTags.size)
        assertEquals("Work", viewModel.uiState.value.allTags[0].name)
    }

    @Test
    fun `search query filters tags`() = runTest {
        repo.createTag("Important")
        repo.createTag("Personal")
        repo.createTag("Work")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("imp")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.allTags.size)
        assertEquals("Important", viewModel.uiState.value.allTags[0].name)
    }

    @Test
    fun `clear search shows all tags`() = runTest {
        repo.createTag("One")
        repo.createTag("Two")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("One")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.allTags.size)

        viewModel.setSearchQuery("")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.allTags.size)
    }

    @Test
    fun `createTag via viewModel`() = runTest {
        viewModel.createTag("Work")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.allTags.size)
        assertEquals("Work", viewModel.uiState.value.allTags[0].name)
    }

    @Test
    fun `deleteSelected through confirmation flow`() = runTest {
        val id1 = repo.createTag("Tag1")
        val id2 = repo.createTag("Tag2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.enterSelectionMode(id1)
        viewModel.toggleSelection(id2)
        viewModel.showDeleteConfirmation()
        assertTrue(viewModel.uiState.value.showDeleteConfirmation)
        viewModel.confirmDelete()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.allTags.size)
        assertFalse(viewModel.uiState.value.selectionMode)
    }

    @Test
    fun `delete single tag through confirmation flow`() = runTest {
        repo.createTag("Tag1")
        repo.createTag("Tag2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val tagId = viewModel.uiState.value.allTags[0].id
        viewModel.showDeleteConfirmationForTag(tagId)
        assertTrue(viewModel.uiState.value.showDeleteConfirmation)
        assertEquals(tagId, viewModel.uiState.value.pendingDeleteTagId)
        viewModel.confirmDelete()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.allTags.size)
    }

    @Test
    fun `hideDeleteConfirmation clears state`() {
        viewModel.showDeleteConfirmationForTag(1L)
        viewModel.hideDeleteConfirmation()
        assertFalse(viewModel.uiState.value.showDeleteConfirmation)
    }

    @Test
    fun `enterSelectionMode activates selection`() {
        viewModel.enterSelectionMode(1L)
        assertTrue(viewModel.uiState.value.selectionMode)
        assertEquals(setOf(1L), viewModel.uiState.value.selectedTagIds)
    }

    @Test
    fun `toggleSelection adds and removes`() {
        viewModel.enterSelectionMode(1L)
        viewModel.toggleSelection(2L)
        assertTrue(2L in viewModel.uiState.value.selectedTagIds)
        viewModel.toggleSelection(1L)
        assertFalse(1L in viewModel.uiState.value.selectedTagIds)
    }

    @Test
    fun `exitSelectionMode clears state`() {
        viewModel.enterSelectionMode(1L)
        viewModel.exitSelectionMode()
        assertFalse(viewModel.uiState.value.selectionMode)
        assertTrue(viewModel.uiState.value.selectedTagIds.isEmpty())
    }
}
