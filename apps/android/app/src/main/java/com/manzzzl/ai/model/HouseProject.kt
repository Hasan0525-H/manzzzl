package com.manzzzl.ai.model

/**
 * Main project state shared across upload, analysis and 3D generation.
 */
data class HouseProject(
    val id: String = "",
    val name: String = "",
    val floorPlanPath: String? = null,
    val city: String = "",
    val floors: Int = 1,
    val analysisStatus: String = "PENDING",
    val modelPath: String? = null
)
