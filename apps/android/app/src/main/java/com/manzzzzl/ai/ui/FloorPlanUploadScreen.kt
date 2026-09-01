package com.manzzzl.ai.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun FloorPlanUploadScreen(onAnalyze: () -> Unit) {
    Button(onClick = onAnalyze) {
        Text("رفع مخطط 2D")
    }
}
