package com.manzzzl.ai.analysis

/** Intermediate result between 2D plan analysis and 3D generation. */
data class PlanAnalysisResult(
    val sourcePath: String,
    val rooms: List<String> = emptyList(),
    val wallsDetected: Int = 0,
    val doorsDetected: Int = 0,
    val windowsDetected: Int = 0
)
