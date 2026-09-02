package com.manzzzl.ai.analysis

/**
 * Foundation for 2D floor plan processing.
 *
 * This layer will later connect free/open-source image analysis
 * methods for wall, room, door and window extraction.
 */
class PlanImageProcessor {

    fun process(planPath: String): PlanProcessingResult {
        return PlanProcessingResult(
            sourcePath = planPath,
            status = "READY_FOR_ANALYSIS"
        )
    }
}

data class PlanProcessingResult(
    val sourcePath: String,
    val status: String
)
