package com.example.mylens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.mylens.ui.screens.CameraScreen
import com.example.mylens.ui.screens.CropScreen
import com.example.mylens.ui.screens.ExportScreen
import com.example.mylens.ui.screens.HomeScreen
import com.example.mylens.ui.theme.MyLensTheme
import com.example.mylens.viewmodel.ScannerViewModel

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
    // Single ViewModel shared across all screens — holds the page list
    val viewModel: ScannerViewModel = viewModel()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNavigateToCamera = { navController.navigate("camera") },
                onNavigateToExport = { navController.navigate("export") },
                onNavigateToCrop   = { pageId -> navController.navigate("crop/$pageId") },
                viewModel = viewModel
            )
        }
        composable("camera") {
            CameraScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }
        composable("export") {
            ExportScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }
        // pageId is passed as a path segment so CropScreen knows which page to edit
        composable("crop/{pageId}") { backStackEntry ->
            val pageId = backStackEntry.arguments?.getString("pageId") ?: return@composable
            CropScreen(
                pageId = pageId,
                onBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }
    }
}
