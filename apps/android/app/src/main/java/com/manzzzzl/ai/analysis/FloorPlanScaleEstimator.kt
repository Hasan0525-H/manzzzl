package com.manzzzl.ai.analysis

/**
 * Estimates floor plan scale information before 3D mapping.
 * This keeps dimension estimation separate from geometry generation.
 */
object FloorPlanScaleEstimator {

    data class ScaleResult(
        val widthMeters: Double? = null,
        val heightMeters: Double? = null,
        val confidence: Double = 0.0
    )

    fun estimate(
        detectedWidthPixels: Int,
        detectedHeightPixels: Int,
        knownReferenceMeters: Double? = null
    ): ScaleResult {
        if (detectedWidthPixels <= 0 || detectedHeightPixels <= 0) {
            return ScaleResult()
        }

        return if (knownReferenceMeters != null) {
            ScaleResult(
                widthMeters = knownReferenceMeters,
                heightMeters = knownReferenceMeters * detectedHeightPixels / detectedWidthPixels,
                confidence = 0.7
            )
        } else {
            ScaleResult(confidence = 0.2)
        }
    }
}
