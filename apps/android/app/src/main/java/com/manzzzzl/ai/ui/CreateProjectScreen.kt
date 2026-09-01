package com.manzzzl.ai.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun CreateProjectScreen(onUpload: () -> Unit) {
    Button(onClick = onUpload) {
        Text("إنشاء مشروع منزل ورفع المخطط")
    }
}
