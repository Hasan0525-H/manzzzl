package com.manzzzl.ai.ui

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier

@Composable
fun ProcessingScreen() {
    Column {
        Text("جاري تحليل المخطط وتحضير النموذج")
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth()
        )
    }
}
