package com.manzzzl.ai.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun Result3DScreen(
    modelPath: String? = null,
    onOpenModel: () -> Unit = {}
) {
    Text("نتيجة تصميم المنزل 3D")

    Text(
        if (modelPath.isNullOrBlank()) {
            "النموذج قيد التجهيز"
        } else {
            "العرض الخارجي جاهز"
        }
    )

    if (!modelPath.isNullOrBlank()) {
        Button(onClick = onOpenModel) {
            Text("فتح النموذج")
        }
    }
}
