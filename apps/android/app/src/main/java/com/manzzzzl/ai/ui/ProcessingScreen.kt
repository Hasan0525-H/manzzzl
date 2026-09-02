package com.manzzzl.ai.ui

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.ui.Modifier

@Composable
fun ProcessingScreen(onComplete: () -> Unit = {}) {
    var progress by remember { mutableStateOf(0.35f) }

    Column {
        Text("جاري تحليل المخطط وتحضير النموذج")
        Text("كشف الجدران والغرف والفتحات")
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = onComplete) {
            Text("عرض النموذج ثلاثي الأبعاد")
        }
    }
}
