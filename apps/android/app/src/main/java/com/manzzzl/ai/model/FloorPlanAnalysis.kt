package com.manzzzl.ai.model

/**
 * Intermediate representation between 2D image analysis and 3D generation.
 */
data class FloorPlanAnalysis(
    val sourcePath: String,
    val walls: List<String>,
    val rooms: List<String>,
    val doors: List<String>,
    val windows: List<String>
)
