package com.manzzzl.ai.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.manzzzl.ai.screens.*

@Composable
fun NavigationGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = AppRoutes.LOGIN
    ) {
        composable(AppRoutes.LOGIN) {
            LoginScreen {
                navController.navigate(AppRoutes.DASHBOARD)
            }
        }

        composable(AppRoutes.DASHBOARD) {
            ProjectDashboardScreen {
                navController.navigate(AppRoutes.CREATE_PROJECT)
            }
        }

        composable(AppRoutes.CREATE_PROJECT) {
            CreateHomeProjectScreen {
                navController.navigate(AppRoutes.UPLOAD_PLAN)
            }
        }

        composable(AppRoutes.UPLOAD_PLAN) {
            PlanUploadScreen {
                navController.navigate(AppRoutes.PLAN_REVIEW)
            }
        }

        composable(AppRoutes.PLAN_REVIEW) {
            PlanReviewScreen {
                navController.navigate(AppRoutes.HOUSE_SETUP)
            }
        }

        composable(AppRoutes.HOUSE_SETUP) {
            HouseSetupScreen {
                navController.navigate(AppRoutes.PROCESSING)
            }
        }

        composable(AppRoutes.PROCESSING) {
            ProcessingScreen {
                navController.navigate(AppRoutes.RESULT_3D)
            }
        }

        composable(AppRoutes.RESULT_3D) {
            Result3DScreen {
                navController.navigate(AppRoutes.MODEL_VIEWER)
            }
        }

        composable(AppRoutes.MODEL_VIEWER) {
            ModelViewerScreen()
        }
    }
}
