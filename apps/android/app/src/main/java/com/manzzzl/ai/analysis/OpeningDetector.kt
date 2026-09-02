package com.manzzzl.ai.analysis

/**
 * Foundation for detecting doors and windows from a floor plan.
 */
class OpeningDetector {
    fun detect(imagePath: String): OpeningDetectionResult {
        return OpeningDetectionResult(
            openings = emptyList()
        )
    }
}

data class OpeningDetectionResult(
    val openings: List<Opening>
)

enum class OpeningType {
    DOOR,
    WINDOW
}

data class Opening(
    val type: OpeningType,
    val x: Float,
    val y: Float
)
