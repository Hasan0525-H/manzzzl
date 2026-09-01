package com.manzzzl.ai.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun FloorPlanUploadScreen(onUpload: () -> Unit) {
    Button(onClick = onUpload) {
        Text("رفع مخطط المنزل")
    }
}
