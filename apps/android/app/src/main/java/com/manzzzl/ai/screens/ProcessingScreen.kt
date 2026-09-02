package com.manzzzl.ai.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ProcessingScreen(onComplete: () -> Unit = {}) {
    Text("جاري تحليل المخطط")

    Text("0% - 100%")

    Button(onClick = onComplete) {
        Text("عرض النتيجة")
    }
}
