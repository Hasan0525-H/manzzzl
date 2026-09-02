package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Dashboard for user projects.
 */
@Composable
fun ProjectDashboardScreen(
    onCreateProject: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "مشاريعي",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "ابدأ بإنشاء تصميم منزلك وتحويل المخطط إلى نموذج ثلاثي الأبعاد"
        )

        Button(onClick = onCreateProject) {
            Text("إنشاء منزل جديد")
        }
    }
}
