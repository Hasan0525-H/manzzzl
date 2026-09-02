package com.manzzzl.ai.viewer

/**
 * State prepared for the 3D renderer.
 * Keeps rendering data separated from UI.
 */
data class ThreeDRenderState(
    val modelPath: String? = null,
    val showWalls: Boolean = true,
    val showFloors: Boolean = true,
    val showOpenings: Boolean = true,
    val showDecorLayer: Boolean = true,
    val cameraZoom: Float = 1f,
    val cameraRotation: Float = 0f
)
