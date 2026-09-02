package com.manzzzl.ai.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.manzzzl.ai.model.ModelRenderer

/**
 * 3D viewer entry point.
 * Rendering implementation remains isolated in ModelRenderer.
 */
@Composable
fun ModelViewerScreen(modelPath: String? = null) {
    val renderModel = modelPath?.let { ModelRenderer().prepare(it) }

    Text("عارض النموذج ثلاثي الأبعاد")

    when {
        renderModel == null -> {
            Text("لم يتم إنشاء نموذج بعد")
        }
        renderModel.ready -> {
            Text("النموذج ${renderModel.format} جاهز")
            Text("تجهيز العرض ثلاثي الأبعاد")
            Text("تدوير - تكبير - استكشاف")
        }
        else -> {
            Text("النموذج غير جاهز للعرض")
        }
    }
}
