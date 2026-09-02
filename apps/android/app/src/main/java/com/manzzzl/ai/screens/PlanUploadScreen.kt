package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PlanUploadScreen(
    onAnalyze: () -> Unit
) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("رفع مخطط المنزل 2D")
        Text("ارفع صورة المخطط ليتم تجهيزه للتحليل والتحويل إلى نموذج ثلاثي الأبعاد")
        Button(onClick = onAnalyze) {
            Text("تحليل المخطط")
        }
    }
}
