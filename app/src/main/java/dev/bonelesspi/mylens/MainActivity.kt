package dev.bonelesspi.mylens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.bonelesspi.mylens.ui.screens.CameraScreen
import dev.bonelesspi.mylens.ui.screens.EditScreen
import dev.bonelesspi.mylens.ui.screens.SelectScreen
import dev.bonelesspi.mylens.ui.screens.SettingsScreen
import dev.bonelesspi.mylens.ui.theme.MyLensTheme
import dev.bonelesspi.mylens.viewmodel.ScannerViewModel
import dev.bonelesspi.mylens.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyLensTheme {
                MyLensApp()
            }
        }
    }
}

@Composable
fun MyLensApp() {
    val navController = rememberNavController()
    val scannerViewModel: ScannerViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()

    NavHost(navController = navController, startDestination = "select") {

        composable("select") {
            SelectScreen(
                onNavigateToCamera   = { navController.navigate("camera") },
                onNavigateToEdit     = { pageId -> navController.navigate("edit/$pageId") },
                onNavigateToSettings = { navController.navigate("settings") },
                viewModel            = scannerViewModel,
                settingsViewModel    = settingsViewModel
            )
        }

        composable("camera") {
            CameraScreen(
                onBack    = { navController.popBackStack() },
                viewModel = scannerViewModel
            )
        }

        composable("edit/{pageId}") { backStackEntry ->
            val pageId = backStackEntry.arguments?.getString("pageId") ?: return@composable
            EditScreen(
                pageId            = pageId,
                onBack            = { navController.popBackStack() },
                viewModel         = scannerViewModel,
                settingsViewModel = settingsViewModel
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack    = { navController.popBackStack() },
                viewModel = settingsViewModel
            )
        }
    }
}
