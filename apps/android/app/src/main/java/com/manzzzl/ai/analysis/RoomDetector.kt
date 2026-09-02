package com.manzzzl.ai.analysis

/**
 * Foundation for extracting rooms from detected floor plan geometry.
 */
class RoomDetector {
    fun detect(walls: WallDetectionResult): RoomDetectionResult {
        return RoomDetectionResult(
            rooms = emptyList()
        )
    }
}

data class RoomDetectionResult(
    val rooms: List<RoomRegion>
)

data class RoomRegion(
    val id: String,
    val label: String?
)
