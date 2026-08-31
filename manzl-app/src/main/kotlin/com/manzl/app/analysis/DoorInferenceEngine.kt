package com.manzl.app.analysis

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import kotlin.math.abs

/**
 * Geometry-first doorway inference for the offline pipeline.
 *
 * A geometry-only door can exist only where measured wall runs already contain an opening-sized
 * gap. [MeasuredOpeningGapDetector] owns that host geometry and works in the wall's local axis, so
 * diagonal and arbitrary-angle walls receive the same treatment as horizontal/vertical walls.
 *
 * Gap width alone is intentionally not enough: both supporting runs must be trusted, nearly
 * collinear and have compatible measured wall thickness. The confidence stays capped below strong
 * symbol evidence because a narrow measured gap can still be a window; OpeningSemanticReconciler
 * resolves that ambiguity later when a real window double-line or door swing symbol exists.
 */
internal object DoorInferenceEngine {

    fun infer(plan: FloorPlan): List<DoorOpening> =
        MeasuredOpeningGapDetector.detect(
            walls = plan.walls,
            minWidthMeters = MIN_DOOR_WIDTH_METERS,
            maxWidthMeters = MAX_DOOR_WIDTH_METERS,
            maxResults = MAX_DOOR_CANDIDATES,
        )
            .mapNotNull { gap ->
                if (gap.supportConfidence < MIN_SUPPORT_WALL_CONFIDENCE) return@mapNotNull null
                val confidence = contextConfidence(gap)
                if (confidence < MIN_DOOR_CONFIDENCE) return@mapNotNull null
                DoorOpening(
                    center = gap.center,
                    widthMeters = gap.widthMeters,
                    rotationDegrees = gap.rotationDegrees,
                    confidence = confidence,
                )
            }
            .sortedByDescending { it.confidence }

    private fun contextConfidence(gap: MeasuredOpeningGapDetector.Gap): Float {
        val widthScore = gapConfidence(gap.widthMeters)
        return (
            gap.supportConfidence.coerceIn(0f, 1f) * 0.46f +
                widthScore * 0.38f +
                gap.thicknessAgreement.coerceIn(0f, 1f) * 0.16f
            ).coerceIn(0f, MAX_GEOMETRY_ONLY_DOOR_CONFIDENCE)
    }

    private fun gapConfidence(width: Float): Float {
        val delta = abs(width - IDEAL_DOOR_WIDTH_METERS)
        return (0.96f - delta * 0.24f).coerceIn(0.66f, 0.96f)
    }

    private const val MIN_DOOR_WIDTH_METERS = 0.68f
    private const val MAX_DOOR_WIDTH_METERS = 1.45f
    private const val IDEAL_DOOR_WIDTH_METERS = 0.95f
    private const val MIN_SUPPORT_WALL_CONFIDENCE = 0.60f
    private const val MIN_DOOR_CONFIDENCE = 0.70f
    private const val MAX_GEOMETRY_ONLY_DOOR_CONFIDENCE = 0.90f
    private const val MAX_DOOR_CANDIDATES = 64
}
