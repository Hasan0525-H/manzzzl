package com.manzzzl.ai.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Upload entry point. File picker integration is injected by the caller.
 */
@Composable
fun PlanUploadScreen(
    onPlanSelected: (String) -> Unit = {},
    onContinue: () -> Unit = {}
) {
    Text("رفع المخطط")

    Button(onClick = {
        onPlanSelected("")
    }) {
        Text("اختيار المخطط")
    }

    Button(onClick = onContinue) {
        Text("متابعة")
    }
}
