package com.manzzzl.ai.analysis

/**
 * Unified result produced after analyzing a 2D floor plan.
 * This keeps the pipeline ready for future 3D reconstruction.
 */
data class PlanAnalysisResult(
    val sourcePath: String,
    val walls: List<WallSegment> = emptyList(),
    val rooms: List<DetectedRoom> = emptyList(),
    val openings: List<Opening> = emptyList()
)

data class WallSegment(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)

data class DetectedRoom(
    val name: String? = null,
    val area: Float? = null
)

data class Opening(
    val type: String,
    val position: String? = null
)
