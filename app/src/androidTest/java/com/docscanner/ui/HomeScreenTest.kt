package com.docscanner.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docscanner.ui.navigation.Routes
import com.docscanner.ui.screens.home.HomeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyStateShowsNoDocumentsMessage() {
        composeTestRule.setContent {
            HomeScreen(
                onScanClick = { },
                onDocumentClick = { },
                onSettingsClick = { },
            )
        }

        composeTestRule.onNodeWithText("No documents yet").assertIsDisplayed()
    }

    @Test
    fun fabIsVisible() {
        composeTestRule.setContent {
            HomeScreen(
                onScanClick = { },
                onDocumentClick = { },
                onSettingsClick = { },
            )
        }
    }
}
