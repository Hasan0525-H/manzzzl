package com.manzl.app.analysis

import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Geometry-preserving topology reconciliation for the final arbitrary-angle wall graph.
 *
 * The legacy [StructuralTopologyReconciler] is intentionally an orthogonal bootstrap helper. Ultra
 * reconstruction later adds/refines walls from the student, paired wall faces, OpenCV and MobileSAM,
 * so the final graph can contain any architectural angle. This pass handles that graph without
 * projecting it back onto horizontal/vertical axes.
 *
 * Safety rules are deliberately strict:
 * - collinear runs are merged only when they already overlap; a positive gap is never bridged here;
 * - parallel wall faces are kept separate unless their centre-lines are effectively duplicates;
 * - a near-miss T/corner endpoint may move only to the mathematical intersection of both measured
 *   wall axes, only across a small thickness-scaled distance, and only when the walls are clearly
 *   non-parallel;
 * - exact/interior crossings are left untouched;
 * - no style prior, room hypothesis or opening width participates in the geometry edit.
 *
 * Every caller must re-run raster fidelity after this pass before exposing architectural 3D.
 */
internal object GeneralWallTopologyReconciler {

    fun reconcile(source: List<WallSegment>): List<WallSegment> {
        if (source.isEmpty()) return emptyList()
        val valid = source
            .filter { wall -> length(wall) >= MIN_WALL_LENGTH_METERS }
            .map(::canonicalDirection)
            .sortedWith(
                compareByDescending<WallSegment> { it.confidence }
                    .thenByDescending { length(it) }
            )

        val deduplicated = mergeOnlyExistingOverlaps(valid).toMutableList()
        snapMeasuredNearMissIntersections(deduplicated)
        return mergeOnlyExistingOverlaps(deduplicated)
            .filter { length(it) >= MIN_WALL_LENGTH_METERS }
            .map(::canonicalDirection)
            .sortedWith(
                compareBy<WallSegment> { min(it.start.z, it.end.z) }
                    .thenBy { min(it.start.x, it.end.x) }
                    .thenBy { max(it.start.z, it.end.z) }
                    .thenBy { max(it.start.x, it.end.x) }
            )
    }

    private fun mergeOnlyExistingOverlaps(source: List<WallSegment>): List<WallSegment> {
        val result = ArrayList<WallSegment>()
        for (candidate in source) {
            val index = result.indexOfFirst { existing -> canMergeExistingOverlap(existing, candidate) }
            if (index < 0) {
                result += candidate
            } else {
                result[index] = mergeOverlap(result[index], candidate)
            }
        }
        return result
    }

    private fun canMergeExistingOverlap(a: WallSegment, b: WallSegment): Boolean {
        val aLength = length(a)
        val bLength = length(b)
        if (aLength <= EPSILON || bLength <= EPSILON) return false
        val aux = (a.end.x - a.start.x) / aLength
        val auz = (a.end.z - a.start.z) / aLength
        val bux = (b.end.x - b.start.x) / bLength
        val buz = (b.end.z - b.start.z) / bLength
        if (abs(aux * bux + auz * buz) < COS_DUPLICATE_ANGLE) return false

        val bMid = midpoint(b)
        val perpendicular = abs((bMid.x - a.start.x) * -auz + (bMid.z - a.start.z) * aux)
        val lineTolerance = min(
            MAX_DUPLICATE_LINE_DISTANCE_METERS,
            max(MIN_DUPLICATE_LINE_DISTANCE_METERS, min(a.thicknessMeters, b.thicknessMeters) * DUPLICATE_THICKNESS_RATIO),
        )
        if (perpendicular > lineTolerance) return false

        val a0 = 0f
        val a1 = aLength
        val b0Raw = projection(b.start, a.start, aux, auz)
        val b1Raw = projection(b.end, a.start, aux, auz)
        val b0 = min(b0Raw, b1Raw)
        val b1 = max(b0Raw, b1Raw)
        val overlap = min(a1, b1) - max(a0, b0)
        // Zero/positive-gap fragments are intentionally not merged. Even a small gap may be an
        // architectural recess/opening; the fidelity/review layer decides what to do with it.
        return overlap >= MIN_EXISTING_OVERLAP_METERS
    }

    private fun mergeOverlap(anchor: WallSegment, other: WallSegment): WallSegment {
        val anchorLength = length(anchor).coerceAtLeast(EPSILON)
        var ux = (anchor.end.x - anchor.start.x) / anchorLength
        var uz = (anchor.end.z - anchor.start.z) / anchorLength
        val otherDx = other.end.x - other.start.x
        val otherDz = other.end.z - other.start.z
        if (ux * otherDx + uz * otherDz < 0f) {
            ux = -ux
            uz = -uz
        }

        val points = listOf(anchor.start, anchor.end, other.start, other.end)
        val projections = points.map { projection(it, anchor.start, ux, uz) }
        val from = projections.minOrNull() ?: 0f
        val to = projections.maxOrNull() ?: anchorLength
        val anchorWeight = anchorLength * anchor.confidence.coerceAtLeast(0.25f)
        val otherLength = length(other).coerceAtLeast(EPSILON)
        val otherWeight = otherLength * other.confidence.coerceAtLeast(0.25f)
        val total = anchorWeight + otherWeight
        val thickness = (
            anchor.thicknessMeters * anchorWeight + other.thicknessMeters * otherWeight
            ) / total.coerceAtLeast(EPSILON)

        return WallSegment(
            start = Vec2(anchor.start.x + ux * from, anchor.start.z + uz * from),
            end = Vec2(anchor.start.x + ux * to, anchor.start.z + uz * to),
            thicknessMeters = thickness,
            heightMeters = max(anchor.heightMeters, other.heightMeters),
            confidence = max(anchor.confidence, other.confidence),
        )
    }

    private fun snapMeasuredNearMissIntersections(walls: MutableList<WallSegment>) {
        if (walls.size < 2) return
        for (i in 0 until walls.lastIndex) {
            for (j in i + 1 until walls.size) {
                val a = walls[i]
                val b = walls[j]
                val intersection = infiniteIntersection(a, b) ?: continue
                if (intersection.angleSine < MIN_INTERSECTION_ANGLE_SINE) continue

                val aLength = length(a)
                val bLength = length(b)
                if (aLength <= EPSILON || bLength <= EPSILON) continue
                val maxSnap = min(
                    MAX_ENDPOINT_SNAP_METERS,
                    max(
                        MIN_ENDPOINT_SNAP_METERS,
                        max(a.thicknessMeters, b.thicknessMeters) * ENDPOINT_SNAP_THICKNESS_RATIO,
                    ),
                )

                val aOutside = parameterOutsideDistance(intersection.tA, aLength)
                val bOutside = parameterOutsideDistance(intersection.tB, bLength)
                val aNear = aOutside in EPSILON..maxSnap
                val bNear = bOutside in EPSILON..maxSnap
                val aInside = intersection.tA in 0f..1f
                val bInside = intersection.tB in 0f..1f

                // Valid T: one measured endpoint stops just before the interior of another wall.
                // Valid corner: both endpoints stop just before their common mathematical crossing.
                val snapA = aNear && (bInside || bNear)
                val snapB = bNear && (aInside || aNear)
                if (!snapA && !snapB) continue

                if (snapA) walls[i] = snapNearestOutsideEndpoint(walls[i], intersection.point)
                if (snapB) walls[j] = snapNearestOutsideEndpoint(walls[j], intersection.point)
            }
        }
    }

    private fun infiniteIntersection(a: WallSegment, b: WallSegment): Intersection? {
        val arx = a.end.x - a.start.x
        val arz = a.end.z - a.start.z
        val brx = b.end.x - b.start.x
        val brz = b.end.z - b.start.z
        val aLength = sqrt(arx * arx + arz * arz)
        val bLength = sqrt(brx * brx + brz * brz)
        if (aLength <= EPSILON || bLength <= EPSILON) return null
        val denominator = arx * brz - arz * brx
        val angleSine = abs(denominator) / (aLength * bLength)
        if (abs(denominator) <= EPSILON) return null
        val qx = b.start.x - a.start.x
        val qz = b.start.z - a.start.z
        val t = (qx * brz - qz * brx) / denominator
        val u = (qx * arz - qz * arx) / denominator
        return Intersection(
            point = Vec2(a.start.x + arx * t, a.start.z + arz * t),
            tA = t,
            tB = u,
            angleSine = angleSine,
        )
    }

    private fun parameterOutsideDistance(parameter: Float, segmentLength: Float): Float = when {
        parameter < 0f -> -parameter * segmentLength
        parameter > 1f -> (parameter - 1f) * segmentLength
        else -> 0f
    }

    private fun snapNearestOutsideEndpoint(wall: WallSegment, point: Vec2): WallSegment {
        val startDistance = distance(wall.start, point)
        val endDistance = distance(wall.end, point)
        return if (startDistance <= endDistance) wall.copy(start = point) else wall.copy(end = point)
    }

    private fun canonicalDirection(wall: WallSegment): WallSegment {
        val shouldSwap = wall.start.x > wall.end.x + EPSILON ||
            (abs(wall.start.x - wall.end.x) <= EPSILON && wall.start.z > wall.end.z)
        return if (shouldSwap) wall.copy(start = wall.end, end = wall.start) else wall
    }

    private fun projection(point: Vec2, origin: Vec2, ux: Float, uz: Float): Float =
        (point.x - origin.x) * ux + (point.z - origin.z) * uz

    private fun midpoint(wall: WallSegment): Vec2 = Vec2(
        (wall.start.x + wall.end.x) * 0.5f,
        (wall.start.z + wall.end.z) * 0.5f,
    )

    private fun length(wall: WallSegment): Float = distance(wall.start, wall.end)

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = b.x - a.x
        val dz = b.z - a.z
        return sqrt(dx * dx + dz * dz)
    }

    private data class Intersection(
        val point: Vec2,
        val tA: Float,
        val tB: Float,
        val angleSine: Float,
    )

    private const val MIN_WALL_LENGTH_METERS = 0.24f
    private const val MIN_EXISTING_OVERLAP_METERS = 0.04f
    private const val MIN_DUPLICATE_LINE_DISTANCE_METERS = 0.018f
    private const val MAX_DUPLICATE_LINE_DISTANCE_METERS = 0.065f
    private const val DUPLICATE_THICKNESS_RATIO = 0.30f
    private const val MIN_ENDPOINT_SNAP_METERS = 0.035f
    private const val MAX_ENDPOINT_SNAP_METERS = 0.12f
    private const val ENDPOINT_SNAP_THICKNESS_RATIO = 0.58f
    private val COS_DUPLICATE_ANGLE = cos(4.0 * PI / 180.0).toFloat()
    private val MIN_INTERSECTION_ANGLE_SINE = kotlin.math.sin(12.0 * PI / 180.0).toFloat()
    private const val EPSILON = 0.000001f
}
