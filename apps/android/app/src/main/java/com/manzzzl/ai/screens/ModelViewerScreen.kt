package com.manzzzl.ai.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.manzzzl.ai.model.ModelRenderer

/**
 * 3D viewer entry point.
 * Rendering implementation is isolated in ModelRenderer.
 */
@Composable
fun ModelViewerScreen(modelPath: String? = null) {
    val renderer = ModelRenderer()
    val result = modelPath?.let { renderer.prepare(it) }

    Text("عارض النموذج ثلاثي الأبعاد")

    when {
        result == null -> Text("لم يتم إنشاء نموذج بعد")
        result.ready -> {
            Text("النموذج ${result.format} جاهز")
            Text("تدوير - تكبير - استكشاف")
        }
        else -> Text("النموذج غير جاهز للعرض")
    }
}
