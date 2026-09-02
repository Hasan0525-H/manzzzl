package com.manzzzl.ai.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Upload entry point with real Android file picker integration.
 */
@Composable
fun PlanUploadScreen(
    onPlanSelected: (String) -> Unit = {},
    onContinue: () -> Unit = {}
) {
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onPlanSelected(it.toString()) }
    }

    Text("رفع المخطط")

    Button(onClick = {
        picker.launch(arrayOf("image/*", "application/pdf"))
    }) {
        Text("اختيار المخطط")
    }

    Button(onClick = onContinue) {
        Text("متابعة")
    }
}
