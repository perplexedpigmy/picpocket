package com.docscanner.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.docscanner.ui.screens.detail.DocumentDetailScreen
import com.docscanner.ui.screens.home.HomeScreen
import com.docscanner.ui.screens.scanner.ScannerScreen
import com.docscanner.ui.screens.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SCANNER = "scanner"
    const val DOCUMENT_DETAIL = "document/{documentId}"
    const val SETTINGS = "settings"

    fun documentDetail(documentId: Long) = "document/$documentId"
}

@Composable
fun DocScannerNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onScanClick = { navController.navigate(Routes.SCANNER) },
                onDocumentClick = { docId -> navController.navigate(Routes.documentDetail(docId)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SCANNER) {
            ScannerScreen(
                onNavigateBack = { navController.popBackStack() },
                onDocumentSaved = { docId ->
                    navController.popBackStack()
                    navController.navigate(Routes.documentDetail(docId))
                },
            )
        }
        composable(Routes.DOCUMENT_DETAIL) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getString("documentId")?.toLongOrNull()
            if (documentId != null) {
                DocumentDetailScreen(
                    documentId = documentId,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
