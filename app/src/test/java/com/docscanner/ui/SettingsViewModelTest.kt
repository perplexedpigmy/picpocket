package com.docscanner.ui

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.docscanner.ui.screens.settings.SettingsViewModel
import com.docscanner.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

class SettingsViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `default state has searchable PDF enabled`() {
        val state = viewModel.uiState.value
        assertTrue(state.searchablePdf)
    }

    @Test
    fun `toggle searchable PDF off`() {
        viewModel.toggleSearchablePdf(false)
        assertFalse(viewModel.uiState.value.searchablePdf)
    }

    @Test
    fun `toggle searchable PDF back on`() {
        viewModel.toggleSearchablePdf(false)
        viewModel.toggleSearchablePdf(true)
        assertTrue(viewModel.uiState.value.searchablePdf)
    }

    @Test
    fun `set default save URI`() {
        val uri = Uri.parse("content://com.android.externalstorage/doc/primary/Documents")
        viewModel.setDefaultSaveUri(uri)
        assertNotNull(viewModel.uiState.value.defaultSaveUri)
        assertTrue(viewModel.uiState.value.defaultSaveLabel.isNotEmpty())
    }
}
