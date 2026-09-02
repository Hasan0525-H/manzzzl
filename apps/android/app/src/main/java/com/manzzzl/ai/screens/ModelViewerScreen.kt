package com.manzzzl.ai.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * 3D viewer entry point.
 * The rendering engine can be replaced without changing navigation flow.
 */
@Composable
fun ModelViewerScreen(modelPath: String? = null) {
    Text("عارض النموذج ثلاثي الأبعاد")

    when {
        modelPath.isNullOrBlank() -> {
            Text("لم يتم إنشاء نموذج بعد")
        }
        modelPath.startsWith("glb://") -> {
            Text("تحميل نموذج GLB")
            Text("تدوير - تكبير - استكشاف")
        }
        else -> {
            Text("النموذج جاهز للاستكشاف")
            Text("تجهيز محرك العرض")
        }
    }
}
