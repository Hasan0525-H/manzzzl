package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Fail-closed topology inspection for wall junctions after raster extraction.
 *
 * Aggregate raster coverage can still look strong when one T-junction stops a few centimetres short
 * of another wall. That defect becomes a visible crack in 3D and can split room topology. This pass
 * does not move geometry. It only identifies high-confidence, non-parallel wall endpoints that stop
 * near the interior of another trusted wall while their physical wall solids still do not touch.
 *
 * Collinear gaps are deliberately ignored because they may be doors/windows. Corner-to-corner gaps
 * are also ignored here to avoid guessing about separate wall runs; they remain covered by raster
 * fidelity and manual review.
 */
internal object WallTopologyIntegrity {

    data class NearMissJunction(
        val sourceWallIndex: Int,
        val targetWallIndex: Int,
        val endpoint: Vec2,
        val physicalGapMeters: Float,
        val severity: Float,
    )

    fun findNearMissJunctions(plan: FloorPlan): List<NearMissJunction> {
        if (plan.walls.size < 2) return emptyList()
        val trusted = plan.walls.mapIndexedNotNull { index, wall ->
            val length = length(wall)
            if (wall.confidence < MIN_WALL_CONFIDENCE || length < MIN_WALL_LENGTH_METERS) null
            else IndexedWall(index, wall, length)
        }
        if (trusted.size < 2) return emptyList()

        val result = ArrayList<NearMissJunction>()
        for (source in trusted) {
            for (endpoint in listOf(source.wall.start, source.wall.end)) {
                var best: NearMissJunction? = null
                for (target in trusted) {
                    if (source.index == target.index) continue
                    if (isNearlyParallel(source.wall, source.length, target.wall, target.length)) continue

                    val projection = projection(endpoint, target.wall) ?: continue
                    if (projection.t !in TARGET_INTERIOR_MIN_T..TARGET_INTERIOR_MAX_T) continue

                    val contactDistance =
                        source.wall.thicknessMeters.coerceAtLeast(MIN_WALL_THICKNESS_METERS) * 0.5f +
                            target.wall.thicknessMeters.coerceAtLeast(MIN_WALL_THICKNESS_METERS) * 0.5f +
                            CONTACT_TOLERANCE_METERS
                    val physicalGap = projection.distance - contactDistance
                    if (physicalGap !in MIN_VISIBLE_GAP_METERS..MAX_NEAR_MISS_GAP_METERS) continue

                    val confidence = minOf(source.wall.confidence, target.wall.confidence).coerceIn(0f, 1f)
                    val gapScore = (
                        1f -
                            (physicalGap - MIN_VISIBLE_GAP_METERS) /
                            (MAX_NEAR_MISS_GAP_METERS - MIN_VISIBLE_GAP_METERS)
                        ).coerceIn(0f, 1f)
                    val severity = (confidence * 0.78f + gapScore * 0.22f).coerceIn(0f, 1f)
                    if (severity < MIN_CRITICAL_SEVERITY) continue

                    val candidate = NearMissJunction(
                        sourceWallIndex = source.index,
                        targetWallIndex = target.index,
                        endpoint = endpoint,
                        physicalGapMeters = physicalGap,
                        severity = severity,
                    )
                    if (best == null || candidate.severity > best.severity) best = candidate
                }
                if (best != null) result += best
            }
        }

        return result
            .sortedByDescending { it.severity }
            .distinctBy { it.sourceWallIndex to quantizedEndpoint(it.endpoint) }
            .take(MAX_REPORTED_NEAR_MISSES)
    }

    private fun isNearlyParallel(a: WallSegment, aLength: Float, b: WallSegment, bLength: Float): Boolean {
        val aux = (a.end.x - a.start.x) / aLength
        val auz = (a.end.z - a.start.z) / aLength
        val bux = (b.end.x - b.start.x) / bLength
        val buz = (b.end.z - b.start.z) / bLength
        return abs(aux * bux + auz * buz) >= MAX_NON_PARALLEL_ALIGNMENT
    }

    private fun projection(point: Vec2, wall: WallSegment): Projection? {
        val vx = wall.end.x - wall.start.x
        val vz = wall.end.z - wall.start.z
        val lengthSq = vx * vx + vz * vz
        if (lengthSq <= 0.000001f) return null
        val t = ((point.x - wall.start.x) * vx + (point.z - wall.start.z) * vz) / lengthSq
        val clamped = t.coerceIn(0f, 1f)
        val qx = wall.start.x + vx * clamped
        val qz = wall.start.z + vz * clamped
        val dx = point.x - qx
        val dz = point.z - qz
        return Projection(t = clamped, distance = sqrt(dx * dx + dz * dz))
    }

    private fun length(wall: WallSegment): Float {
        val dx = wall.end.x - wall.start.x
        val dz = wall.end.z - wall.start.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun quantizedEndpoint(point: Vec2): Pair<Int, Int> =
        kotlin.math.round(point.x * 100f).toInt() to kotlin.math.round(point.z * 100f).toInt()

    private data class IndexedWall(
        val index: Int,
        val wall: WallSegment,
        val length: Float,
    )

    private data class Projection(
        val t: Float,
        val distance: Float,
    )

    private const val MIN_WALL_CONFIDENCE = 0.72f
    private const val MIN_WALL_LENGTH_METERS = 0.50f
    private const val MIN_WALL_THICKNESS_METERS = 0.08f
    private const val CONTACT_TOLERANCE_METERS = 0.025f
    private const val MIN_VISIBLE_GAP_METERS = 0.035f
    private const val MAX_NEAR_MISS_GAP_METERS = 0.18f
    private const val TARGET_INTERIOR_MIN_T = 0.08f
    private const val TARGET_INTERIOR_MAX_T = 0.92f
    private const val MAX_NON_PARALLEL_ALIGNMENT = 0.94f
    private const val MIN_CRITICAL_SEVERITY = 0.72f
    private const val MAX_REPORTED_NEAR_MISSES = 8
}
