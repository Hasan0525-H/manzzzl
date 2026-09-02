package com.manzzzl.ai.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ModelViewerScreen(modelPath: String? = null) {
    Text("عارض النموذج ثلاثي الأبعاد")

    if (modelPath.isNullOrBlank()) {
        Text("لم يتم إنشاء نموذج بعد")
    } else {
        Text("النموذج جاهز للاستكشاف")
        Text("تدوير - تكبير - استكشاف")
    }
}
