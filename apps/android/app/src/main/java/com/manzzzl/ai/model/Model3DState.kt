package com.manzzzl.ai.model

/**
 * State prepared for the 3D rendering layer.
 * Keeps UI independent from the rendering engine.
 */
data class Model3DState(
    val source: String,
    val format: String = "GLB",
    val ready: Boolean = false
)
