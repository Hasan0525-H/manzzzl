package com.manzzzl.ai.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun Result3DScreen(onOpenModel: () -> Unit = {}) {
    Text("نتيجة تصميم المنزل 3D")

    Text("العرض الخارجي جاهز")

    Button(onClick = onOpenModel) {
        Text("فتح النموذج")
    }
}
