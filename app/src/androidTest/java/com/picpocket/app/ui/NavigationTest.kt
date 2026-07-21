package com.picpocket.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picpocket.app.ui.navigation.PicPocketNavGraph
import com.picpocket.app.ui.navigation.Routes
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeScreenIsStartDestination() {
        composeTestRule.setContent {
            val navController = rememberNavController()
            PicPocketNavGraph(navController = navController)
        }

        composeTestRule.onNodeWithText("PicPocket").assertIsDisplayed()
    }
}
