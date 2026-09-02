package com.manzzzl.ai.analysis

/**
 * Foundation for detecting walls from a 2D floor plan.
 * Implementation will later connect to OpenCV / ML models.
 */
class WallDetector {
    fun detect(imagePath: String): WallDetectionResult {
        return WallDetectionResult(
            source = imagePath,
            walls = emptyList()
        )
    }
}

data class WallDetectionResult(
    val source: String,
    val walls: List<WallSegment>
)

data class WallSegment(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)
