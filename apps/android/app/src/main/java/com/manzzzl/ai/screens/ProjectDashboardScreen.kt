package com.manzzzl.ai.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Dashboard for user projects.
 */
@Composable
fun ProjectDashboardScreen(
    onCreateProject: () -> Unit = {}
) {
    Text("المشاريع")
    Button(onClick = onCreateProject) {
        Text("إنشاء منزل جديد")
    }
}
