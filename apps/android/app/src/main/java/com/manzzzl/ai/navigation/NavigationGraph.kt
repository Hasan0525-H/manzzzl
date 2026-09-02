package com.manzzzl.ai.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

@Composable
fun NavigationGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = AppRoutes.LOGIN
    ) {
        composable(AppRoutes.LOGIN) {
            Text("منزلي AI - دخول")
        }
        composable(AppRoutes.DASHBOARD) {
            Text("المشاريع")
        }
        composable(AppRoutes.CREATE_PROJECT) {
            Text("إنشاء منزل")
        }
        composable(AppRoutes.UPLOAD_PLAN) {
            Text("رفع المخطط")
        }
        composable(AppRoutes.PLAN_REVIEW) {
            Text("مراجعة المخطط")
        }
        composable(AppRoutes.HOUSE_SETUP) {
            Text("إعداد المنزل")
        }
        composable(AppRoutes.PROCESSING) {
            Text("جاري المعالجة")
        }
        composable(AppRoutes.RESULT_3D) {
            Text("نتيجة 3D")
        }
        composable(AppRoutes.MODEL_VIEWER) {
            Text("عارض النموذج")
        }
    }
}
