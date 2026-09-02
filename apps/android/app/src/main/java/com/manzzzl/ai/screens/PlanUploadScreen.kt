package com.manzzzl.ai.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun PlanUploadScreen(onContinue: () -> Unit = {}) {
    Text("رفع المخطط")
    Button(onClick = onContinue) {
        Text("متابعة")
    }
}
