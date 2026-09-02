package com.manzzzl.ai.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun PlanReviewScreen(onConfirm: () -> Unit = {}) {
    Text("مراجعة المخطط")
    Button(onClick = onConfirm) {
        Text("تأكيد المخطط")
    }
}
