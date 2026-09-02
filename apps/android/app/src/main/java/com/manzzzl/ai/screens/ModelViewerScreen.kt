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
    Text("عارض النموذج ثلاثي الأبعاد")

    val renderer = ModelRenderer()
    val result = renderer.prepare(modelPath)

    Text(result)
}
