package com.manzzzl.ai.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ProcessingScreen(progress: Int = 0) {
    Text("جاري تحليل المخطط: $progress%")
}
