package com.manzzzl.ai.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun HomeSetupScreen(onCreateProject: () -> Unit) {
    Button(onClick = onCreateProject) {
        Text("إنشاء مشروع منزل")
    }
}
