package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
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
 * Courtyard/shaft classes may only label an already reconstructed closed room polygon; they can never
 * create a void polygon by themselves. Column components are retained by the student cache for a
 * dedicated raster-verified structural primitive pass and are deliberately ignored here.
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

                StudentSemanticComponentDecoder.COURTYARD_CLASS_ID -> {
                    roomLabelEvidence(
                        plan = seed,
                        center = planCenter,
                        componentConfidence = component.confidence,
                        label = COURTYARD_LABEL,
                    )?.let(result::add)
                }

                StudentSemanticComponentDecoder.SHAFT_CLASS_ID -> {
                    roomLabelEvidence(
                        plan = seed,
                        center = planCenter,
                        componentConfidence = component.confidence,
                        label = SHAFT_LABEL,
                    )?.let(result::add)
                }

                StudentSemanticComponentDecoder.COLUMN_CLASS_ID -> {
                    // Structural mass, not a semantic label. A later source-raster adjudicator must
                    // verify its footprint before it can become canonical 3D geometry.
                }
            }
        }

        return result
            .sortedByDescending { it.confidence }
            .fold(ArrayList<SemanticEvidence>()) { accepted, candidate ->
                val duplicate = accepted.any { existing ->
                    existing.kind == candidate.kind &&
                        existing.label == candidate.label &&
                        distance(existing.center, candidate.center) <= duplicateDistance(candidate.kind)
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

    private fun roomLabelEvidence(
        plan: FloorPlan,
        center: Vec2,
        componentConfidence: Float,
        label: String,
    ): SemanticEvidence? {
        if (componentConfidence < MIN_ROOM_LABEL_COMPONENT_CONFIDENCE) return null
        val room = plan.rooms
            .asSequence()
            .filter { it.confidence >= MIN_ROOM_HOST_CONFIDENCE && it.polygon.size >= 3 }
            .filter { pointInsidePolygon(center, it.polygon) }
            .minByOrNull { polygonArea(it) }
            ?: return null

        // The student's class is allowed to add a label only; the room polygon remains exactly the
        // polygon already reconstructed from measured walls/openings.
        return SemanticEvidence(
            kind = SemanticKind.ROOM,
            center = polygonCentroid(room),
            polygon = room.polygon,
            label = label,
            confidence = (
                componentConfidence.coerceIn(0f, 1f) * 0.72f +
                    room.confidence.coerceIn(0f, 1f) * 0.28f
                ).coerceIn(0f, MAX_ROOM_LABEL_CONFIDENCE),
            source = EvidenceSource.LOCAL_AI,
        )
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

    private fun pointInsidePolygon(point: Vec2, polygon: List<Vec2>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val crosses = (current.z > point.z) != (previous.z > point.z)
            if (crosses) {
                val denominator = previous.z - current.z
                val safe = if (abs(denominator) < EPSILON) EPSILON else denominator
                val boundaryX = (previous.x - current.x) * (point.z - current.z) / safe + current.x
                if (point.x < boundaryX) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun polygonArea(room: RoomRegion): Float = polygonArea(room.polygon)

    private fun polygonArea(points: List<Vec2>): Float {
        if (points.size < 3) return 0f
        var sum = 0f
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            sum += a.x * b.z - b.x * a.z
        }
        return abs(sum) * 0.5f
    }

    private fun polygonCentroid(room: RoomRegion): Vec2 {
        if (room.polygon.isEmpty()) return Vec2(0f, 0f)
        return Vec2(
            room.polygon.sumOf { it.x.toDouble() }.toFloat() / room.polygon.size,
            room.polygon.sumOf { it.z.toDouble() }.toFloat() / room.polygon.size,
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
    private const val MIN_ROOM_LABEL_COMPONENT_CONFIDENCE = 0.68f
    private const val MIN_ROOM_HOST_CONFIDENCE = 0.66f
    private const val MAX_ROOM_LABEL_CONFIDENCE = 0.96f
    private const val COURTYARD_LABEL = "courtyard"
    private const val SHAFT_LABEL = "shaft"
    private const val EPSILON = 0.000001f
}
