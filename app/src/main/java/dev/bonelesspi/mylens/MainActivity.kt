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
import dev.bonelesspi.mylens.ui.screens.ExportScreen
import dev.bonelesspi.mylens.ui.screens.SelectScreen
import dev.bonelesspi.mylens.ui.theme.MyLensTheme
import dev.bonelesspi.mylens.viewmodel.ScannerViewModel

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
    val viewModel: ScannerViewModel = viewModel()

    // SelectScreen is the start destination — no Home screen
    NavHost(navController = navController, startDestination = "select") {

        composable("select") {
            SelectScreen(
                onNavigateToCamera = { navController.navigate("camera") },
                onNavigateToEdit   = { pageId -> navController.navigate("edit/$pageId") },
                onNavigateToExport = { navController.navigate("export") },
                viewModel = viewModel
            )
        }

        composable("camera") {
            CameraScreen(
                onBack    = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable("edit/{pageId}") { backStackEntry ->
            val pageId = backStackEntry.arguments?.getString("pageId") ?: return@composable
            EditScreen(
                pageId    = pageId,
                onBack    = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable("export") {
            ExportScreen(
                onBack    = { navController.popBackStack() },
                viewModel = viewModel
            )
        }
    }
}
