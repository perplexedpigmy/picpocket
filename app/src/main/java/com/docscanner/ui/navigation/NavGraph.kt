package com.docscanner.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.docscanner.ui.screens.detail.DocumentDetailScreen
import com.docscanner.ui.screens.home.HomeScreen
import com.docscanner.ui.screens.scanner.ScannerScreen
import com.docscanner.ui.screens.donate.DonateScreen
import com.docscanner.ui.screens.settings.SettingsScreen
import com.docscanner.ui.screens.tags.TagManagementScreen
import com.docscanner.ui.screens.viewer.PageViewerScreen

object Routes {
    const val HOME = "home"
    const val SCANNER = "scanner"
    const val APPEND_SCANNER = "scanner/{documentId}"
    const val DOCUMENT_DETAIL = "document/{documentId}"
    const val PAGE_VIEWER = "viewer/{documentId}/{pageIndex}"
    const val SETTINGS = "settings"
    const val DONATE = "donate"
    const val TAGS = "tags"

    fun documentDetail(documentId: String) = "document/$documentId"
    fun pageViewer(documentId: String, pageIndex: Int) = "viewer/$documentId/$pageIndex"
    fun appendScanner(documentId: String) = "scanner/$documentId"
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
        composable(
            route = Routes.DOCUMENT_DETAIL,
            arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getString("documentId") ?: return@composable
            DocumentDetailScreen(
                documentId = documentId,
                onNavigateBack = { navController.popBackStack() },
                onPageView = { docId, pageIndex ->
                    navController.navigate(Routes.pageViewer(docId, pageIndex))
                },
                onAddPage = { docId ->
                    navController.navigate(Routes.appendScanner(docId))
                },
            )
        }
        composable(
            route = Routes.PAGE_VIEWER,
            arguments = listOf(
                navArgument("documentId") { type = NavType.StringType },
                navArgument("pageIndex") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getString("documentId") ?: return@composable
            val pageIndex = backStackEntry.arguments?.getInt("pageIndex") ?: 0
            PageViewerScreen(
                documentId = documentId,
                initialPageIndex = pageIndex,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.APPEND_SCANNER,
            arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getString("documentId") ?: return@composable
            ScannerScreen(
                documentId = documentId,
                onNavigateBack = { navController.popBackStack() },
                onDocumentSaved = { docId ->
                    navController.popBackStack()
                    navController.navigate(Routes.pageViewer(docId, 0))
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onDonateClick = { navController.navigate(Routes.DONATE) },
                onTagsClick = { navController.navigate(Routes.TAGS) },
            )
        }
        composable(Routes.DONATE) {
            DonateScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Routes.TAGS) {
            TagManagementScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
