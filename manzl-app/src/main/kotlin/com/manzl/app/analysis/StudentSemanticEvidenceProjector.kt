package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Projects student semantic blobs back to the measured plan without letting semantics create topology.
 *
 * Door/window labels only *classify an existing measured wall gap*: centre, width and axis are taken
 * from [MeasuredOpeningGapDetector]. Stair dimensions may come from the semantic component because
 * GeometryEvidenceFusion still applies residential bounds and source-plan containment checks.
 */
internal object StudentSemanticEvidenceProjector {

    fun project(
        components: List<StudentSemanticComponentDecoder.Component>,
        seed: FloorPlan,
        sourceTransform: PlanRasterTransform,
        modelToSource: (Float, Float) -> Pair<Float, Float>?,
        detailPass: Boolean,
    ): List<SemanticEvidence> {
        if (components.isEmpty()) return emptyList()
        val measuredGaps = MeasuredOpeningGapDetector.detect(
            walls = seed.walls,
            minWidthMeters = MIN_OPENING_GAP_METERS,
            maxWidthMeters = MAX_OPENING_GAP_METERS,
            maxResults = MAX_GAPS,
        )
        val result = ArrayList<SemanticEvidence>()

        for (component in components) {
            if (detailPass && component.touchesModelEdge) continue
            val sourceCenter = modelToSource(component.centerX, component.centerY) ?: continue
            val planCenter = sourceTransform.imageToPlan(sourceCenter.first, sourceCenter.second)

            when (component.classId) {
                StudentSemanticComponentDecoder.DOOR_CLASS_ID -> {
                    nearestGap(planCenter, measuredGaps, MIN_DOOR_WIDTH, MAX_DOOR_WIDTH)?.let { gap ->
                        result += SemanticEvidence(
                            kind = SemanticKind.DOOR,
                            center = gap.center,
                            widthMeters = gap.widthMeters,
                            rotationDegrees = gap.rotationDegrees,
                            confidence = fusedClassificationConfidence(component.confidence, gap.supportConfidence),
                            source = EvidenceSource.LOCAL_AI,
                        )
                    }
                }

                StudentSemanticComponentDecoder.WINDOW_CLASS_ID -> {
                    nearestGap(planCenter, measuredGaps, MIN_WINDOW_WIDTH, MAX_WINDOW_WIDTH)?.let { gap ->
                        result += SemanticEvidence(
                            kind = SemanticKind.WINDOW,
                            center = gap.center,
                            widthMeters = gap.widthMeters,
                            rotationDegrees = gap.rotationDegrees,
                            confidence = fusedClassificationConfidence(component.confidence, gap.supportConfidence),
                            source = EvidenceSource.LOCAL_AI,
                        )
                    }
                }

                StudentSemanticComponentDecoder.STAIR_CLASS_ID -> {
                    stairEvidence(component, planCenter, sourceTransform, modelToSource)?.let(result::add)
                }
            }
        }

        return result
            .sortedByDescending { it.confidence }
            .fold(ArrayList<SemanticEvidence>()) { accepted, candidate ->
                val duplicate = accepted.any { existing ->
                    existing.kind == candidate.kind && distance(existing.center, candidate.center) <= duplicateDistance(candidate.kind)
                }
                if (!duplicate) accepted += candidate
                accepted
            }
    }

    private fun nearestGap(
        center: Vec2,
        gaps: List<MeasuredOpeningGapDetector.Gap>,
        minWidth: Float,
        maxWidth: Float,
    ): MeasuredOpeningGapDetector.Gap? {
        var best: MeasuredOpeningGapDetector.Gap? = null
        var bestScore = Float.POSITIVE_INFINITY
        for (gap in gaps) {
            if (gap.widthMeters !in minWidth..maxWidth) continue
            val distance = distance(center, gap.center)
            val allowedDistance = max(MIN_GAP_CENTER_TOLERANCE_METERS, gap.widthMeters * GAP_CENTER_TOLERANCE_RATIO)
            if (distance > allowedDistance) continue
            val score = distance + (1f - gap.supportConfidence.coerceIn(0f, 1f)) * SUPPORT_PENALTY_METERS
            if (score < bestScore) {
                bestScore = score
                best = gap
            }
        }
        return best
    }

    private fun stairEvidence(
        component: StudentSemanticComponentDecoder.Component,
        planCenter: Vec2,
        sourceTransform: PlanRasterTransform,
        modelToSource: (Float, Float) -> Pair<Float, Float>?,
    ): SemanticEvidence? {
        val radians = component.rotationDegrees * PI.toFloat() / 180f
        val ux = cos(radians)
        val uy = sin(radians)
        val nx = -uy
        val ny = ux
        val majorHalf = component.majorSpanPx * 0.5f
        val minorHalf = component.minorSpanPx * 0.5f

        val majorA = modelToSource(
            component.centerX - ux * majorHalf,
            component.centerY - uy * majorHalf,
        ) ?: return null
        val majorB = modelToSource(
            component.centerX + ux * majorHalf,
            component.centerY + uy * majorHalf,
        ) ?: return null
        val minorA = modelToSource(
            component.centerX - nx * minorHalf,
            component.centerY - ny * minorHalf,
        ) ?: return null
        val minorB = modelToSource(
            component.centerX + nx * minorHalf,
            component.centerY + ny * minorHalf,
        ) ?: return null

        val majorPlanA = sourceTransform.imageToPlan(majorA.first, majorA.second)
        val majorPlanB = sourceTransform.imageToPlan(majorB.first, majorB.second)
        val minorPlanA = sourceTransform.imageToPlan(minorA.first, minorA.second)
        val minorPlanB = sourceTransform.imageToPlan(minorB.first, minorB.second)
        val run = distance(majorPlanA, majorPlanB)
        val width = distance(minorPlanA, minorPlanB)
        if (run < MIN_STAIR_RAW_RUN_METERS || width < MIN_STAIR_RAW_WIDTH_METERS) return null

        return SemanticEvidence(
            kind = SemanticKind.STAIR,
            center = planCenter,
            widthMeters = min(width, run),
            lengthMeters = max(width, run),
            rotationDegrees = component.rotationDegrees,
            confidence = component.confidence.coerceIn(0f, 0.96f),
            source = EvidenceSource.LOCAL_AI,
        )
    }

    private fun fusedClassificationConfidence(semantic: Float, structural: Float): Float =
        (semantic.coerceIn(0f, 1f) * 0.82f + structural.coerceIn(0f, 1f) * 0.18f)
            .coerceIn(0f, 0.97f)

    private fun duplicateDistance(kind: SemanticKind): Float = when (kind) {
        SemanticKind.DOOR -> 0.28f
        SemanticKind.WINDOW -> 0.32f
        SemanticKind.STAIR -> 0.55f
        SemanticKind.ROOM -> 0.45f
    }

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private const val MIN_OPENING_GAP_METERS = 0.38f
    private const val MAX_OPENING_GAP_METERS = 4.35f
    private const val MAX_GAPS = 96
    private const val MIN_DOOR_WIDTH = 0.62f
    private const val MAX_DOOR_WIDTH = 1.65f
    private const val MIN_WINDOW_WIDTH = 0.40f
    private const val MAX_WINDOW_WIDTH = 4.20f
    private const val MIN_GAP_CENTER_TOLERANCE_METERS = 0.48f
    private const val GAP_CENTER_TOLERANCE_RATIO = 0.42f
    private const val SUPPORT_PENALTY_METERS = 0.22f
    private const val MIN_STAIR_RAW_RUN_METERS = 1.20f
    private const val MIN_STAIR_RAW_WIDTH_METERS = 0.55f
}
