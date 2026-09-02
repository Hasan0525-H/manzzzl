package com.manzzzl.ai.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.manzzzl.ai.model.ModelRenderer

/**
 * 3D viewer entry point.
 * Rendering engine integration stays isolated from navigation flow.
 */
@Composable
fun ModelViewerScreen(modelPath: String? = null) {
    val renderer = remember { ModelRenderer() }
    val renderModel = modelPath?.let { renderer.prepare(it) }

    Text("عارض النموذج ثلاثي الأبعاد")

    when {
        renderModel == null -> {
            Text("لم يتم إنشاء نموذج بعد")
        }
        renderModel.ready -> {
            // SceneModelView handles GLB download, loading and rendering.
            SceneModelView()
        }
        else -> {
            Text("النموذج غير جاهز للعرض")
        }
    }
}
