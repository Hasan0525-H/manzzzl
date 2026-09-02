package com.manzzzl.ai.threeD

/**
 * State model for the first 3D preview stage.
 * The initial viewer focuses on architectural structure only.
 * Furniture is intentionally excluded.
 */
data class ThreeDViewerState(
    val wallsVisible: Boolean = true,
    val floorsVisible: Boolean = true,
    val openingsVisible: Boolean = true,
    val decorVisible: Boolean = true,
    val rotationEnabled: Boolean = true,
    val zoomEnabled: Boolean = true
)
