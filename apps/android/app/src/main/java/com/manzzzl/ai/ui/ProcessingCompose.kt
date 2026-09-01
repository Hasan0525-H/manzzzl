package com.manzzzl.ai.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun ProcessingCompose(progress: Int = 0) {
    Text("جاري تحليل المنزل: $progress%")
}
