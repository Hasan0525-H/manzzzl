package com.manzzzl.ai.model

/**
 * Rendering abstraction for generated house models.
 * Keeps rendering logic isolated from Compose screens.
 */
class ModelRenderer {
    fun canRender(modelPath: String?): Boolean {
        return !modelPath.isNullOrBlank() && modelPath.startsWith("glb://")
    }

    fun prepare(modelPath: String): RenderModel {
        return RenderModel(
            source = modelPath,
            format = detectFormat(modelPath),
            ready = canRender(modelPath)
        )
    }

    private fun detectFormat(modelPath: String): String {
        return when {
            modelPath.startsWith("glb://") -> "GLB"
            else -> "UNKNOWN"
        }
    }
}

data class RenderModel(
    val source: String,
    val format: String,
    val ready: Boolean
)
