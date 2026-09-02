package com.manzzzl.ai.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ProcessingScreen(
    stage: String = "ANALYZING",
    progress: Int = 0,
    onComplete: () -> Unit = {}
) {
    Text("حالة المعالجة: $stage")
    Text("التقدم: ${progress.coerceIn(0, 100)}%")

    if (progress >= 100) {
        Button(onClick = onComplete) {
            Text("عرض النتيجة")
        }
    }
}
