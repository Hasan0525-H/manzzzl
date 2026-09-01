package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry-first room inference for rectilinear residential plans.
 *
 * The detector never invents a room from style priors. It searches for closed wall-supported
 * rectangles, tolerates realistic door-sized gaps, and rejects candidates crossed by a strong
 * internal divider. Semantic/on-device AI room evidence can still be fused separately; this layer
 * supplies a deterministic topology baseline for ceilings, navigation and quality checks.
 */
internal object RoomInferenceEngine {

    fun infer(plan: FloorPlan): List<RoomRegion> {
        val horizontal = plan.walls.filter(::isHorizontal)
        val vertical = plan.walls.filter(::isVertical)
        if (horizontal.size < 2 || vertical.size < 2) return emptyList()

        val xAxes = clusteredAxes(
            vertical.map { wall ->
                AxisSample(
                    value = (wall.start.x + wall.end.x) * 0.5f,
                    support = abs(wall.end.z - wall.start.z),
                )
            }
        )
        val zAxes = clusteredAxes(
            horizontal.map { wall ->
                AxisSample(
                    value = (wall.start.z + wall.end.z) * 0.5f,
                    support = abs(wall.end.x - wall.start.x),
                )
            }
        )
        if (xAxes.size < 2 || zAxes.size < 2) return emptyList()

        val candidates = ArrayList<RoomCandidate>()
        for (xi in 0 until xAxes.lastIndex) {
            for (xj in xi + 1 until xAxes.size) {
                val x0 = xAxes[xi]
                val x1 = xAxes[xj]
                val width = x1 - x0
                if (width !in MIN_ROOM_SPAN_METERS..MAX_ROOM_SPAN_METERS) continue

                for (zi in 0 until zAxes.lastIndex) {
                    for (zj in zi + 1 until zAxes.size) {
                        val z0 = zAxes[zi]
                        val z1 = zAxes[zj]
                        val depth = z1 - z0
                        if (depth !in MIN_ROOM_SPAN_METERS..MAX_ROOM_SPAN_METERS) continue

                        val area = width * depth
                        if (area !in MIN_ROOM_AREA_SQ_METERS..MAX_ROOM_AREA_SQ_METERS) continue
                        val aspect = max(width, depth) / min(width, depth)
                        if (aspect > MAX_ROOM_ASPECT_RATIO) continue

                        val top = horizontalCoverage(horizontal, z0, x0, x1)
                        if (top < MIN_SIDE_COVERAGE) continue
                        val bottom = horizontalCoverage(horizontal, z1, x0, x1)
                        if (bottom < MIN_SIDE_COVERAGE) continue
                        val left = verticalCoverage(vertical, x0, z0, z1)
                        if (left < MIN_SIDE_COVERAGE) continue
                        val right = verticalCoverage(vertical, x1, z0, z1)
                        if (right < MIN_SIDE_COVERAGE) continue

                        val meanCoverage = (top + bottom + left + right) * 0.25f
                        if (meanCoverage < MIN_MEAN_COVERAGE) continue
                        if (hasStrongInternalDivider(horizontal, vertical, x0, x1, z0, z1)) continue

                        val minCoverage = min(min(top, bottom), min(left, right))
                        val sizePlausibility = when (area) {
                            in 4f..45f -> 1f
                            else -> 0.72f
                        }
                        val confidence = (
                            minCoverage * 0.52f +
                                meanCoverage * 0.38f +
                                sizePlausibility * 0.10f
                            ).coerceIn(0f, 0.96f)

                        candidates += RoomCandidate(
                            x0 = x0,
                            x1 = x1,
                            z0 = z0,
                            z1 = z1,
                            confidence = confidence,
                        )
                    }
                }
            }
        }

        // Prefer the smallest independently enclosed regions. This suppresses an outer building
        // rectangle when several valid rooms already exist inside it.
        val accepted = ArrayList<RoomCandidate>()
        for (candidate in candidates.sortedBy { it.area }) {
            val duplicate = accepted.any { existing -> candidate.nearlySame(existing) }
            if (duplicate) continue

            val enclosesExisting = accepted.any { existing ->
                candidate.contains(existing) && candidate.area > existing.area * ENCLOSURE_AREA_FACTOR
            }
            if (enclosesExisting) continue

            accepted += candidate
            if (accepted.size >= MAX_INFERRED_ROOMS) break
        }

        return accepted.mapIndexed { index, room ->
            RoomRegion(
                id = "geometry-room-${index + 1}-${room.stableKey()}",
                polygon = listOf(
                    Vec2(room.x0, room.z0),
                    Vec2(room.x1, room.z0),
                    Vec2(room.x1, room.z1),
                    Vec2(room.x0, room.z1),
                ),
                label = null,
                confidence = room.confidence,
            )
        }
    }

    private fun clusteredAxes(samples: List<AxisSample>): List<Float> {
        if (samples.isEmpty()) return emptyList()
        val clusters = ArrayList<AxisCluster>()
        for (sample in samples.sortedBy { it.value }) {
            val last = clusters.lastOrNull()
            if (last == null || abs(last.value - sample.value) > AXIS_CLUSTER_TOLERANCE_METERS) {
                clusters += AxisCluster(sample.value, sample.support.coerceAtLeast(0.01f))
            } else {
                val totalSupport = last.support + sample.support.coerceAtLeast(0.01f)
                last.value = (
                    last.value * last.support + sample.value * sample.support.coerceAtLeast(0.01f)
                    ) / totalSupport
                last.support = totalSupport
            }
        }

        val strongest = if (clusters.size <= MAX_AXIS_COORDINATES) {
            clusters
        } else {
            clusters.sortedByDescending { it.support }.take(MAX_AXIS_COORDINATES)
        }
        return strongest.map { it.value }.sorted()
    }

    private fun horizontalCoverage(
        walls: List<WallSegment>,
        z: Float,
        x0: Float,
        x1: Float,
    ): Float {
        val intervals = walls.mapNotNull { wall ->
            val wallZ = (wall.start.z + wall.end.z) * 0.5f
            if (abs(wallZ - z) > BOUNDARY_TOLERANCE_METERS) return@mapNotNull null
            clippedInterval(
                min(wall.start.x, wall.end.x),
                max(wall.start.x, wall.end.x),
                x0,
                x1,
            )
        }
        return intervalCoverage(intervals, x0, x1)
    }

    private fun verticalCoverage(
        walls: List<WallSegment>,
        x: Float,
        z0: Float,
        z1: Float,
    ): Float {
        val intervals = walls.mapNotNull { wall ->
            val wallX = (wall.start.x + wall.end.x) * 0.5f
            if (abs(wallX - x) > BOUNDARY_TOLERANCE_METERS) return@mapNotNull null
            clippedInterval(
                min(wall.start.z, wall.end.z),
                max(wall.start.z, wall.end.z),
                z0,
                z1,
            )
        }
        return intervalCoverage(intervals, z0, z1)
    }

    private fun hasStrongInternalDivider(
        horizontal: List<WallSegment>,
        vertical: List<WallSegment>,
        x0: Float,
        x1: Float,
        z0: Float,
        z1: Float,
    ): Boolean {
        val width = x1 - x0
        val depth = z1 - z0
        val edgeInset = min(INTERNAL_DIVIDER_EDGE_INSET_METERS, min(width, depth) * 0.18f)

        val strongVertical = vertical.any { wall ->
            val wallX = (wall.start.x + wall.end.x) * 0.5f
            if (wallX <= x0 + edgeInset || wallX >= x1 - edgeInset) return@any false
            val from = max(z0, min(wall.start.z, wall.end.z))
            val to = min(z1, max(wall.start.z, wall.end.z))
            (to - from).coerceAtLeast(0f) / depth >= INTERNAL_DIVIDER_COVERAGE
        }
        if (strongVertical) return true

        return horizontal.any { wall ->
            val wallZ = (wall.start.z + wall.end.z) * 0.5f
            if (wallZ <= z0 + edgeInset || wallZ >= z1 - edgeInset) return@any false
            val from = max(x0, min(wall.start.x, wall.end.x))
            val to = min(x1, max(wall.start.x, wall.end.x))
            (to - from).coerceAtLeast(0f) / width >= INTERNAL_DIVIDER_COVERAGE
        }
    }

    private fun intervalCoverage(intervals: List<Pair<Float, Float>>, from: Float, to: Float): Float {
        if (intervals.isEmpty() || to <= from) return 0f
        val sorted = intervals.sortedBy { it.first }
        var covered = 0f
        var currentStart = sorted.first().first
        var currentEnd = sorted.first().second
        for (index in 1 until sorted.size) {
            val interval = sorted[index]
            if (interval.first <= currentEnd + MERGE_INTERVAL_TOLERANCE_METERS) {
                currentEnd = max(currentEnd, interval.second)
            } else {
                covered += (currentEnd - currentStart).coerceAtLeast(0f)
                currentStart = interval.first
                currentEnd = interval.second
            }
        }
        covered += (currentEnd - currentStart).coerceAtLeast(0f)
        return (covered / (to - from)).coerceIn(0f, 1f)
    }

    private fun clippedInterval(
        segmentFrom: Float,
        segmentTo: Float,
        boundaryFrom: Float,
        boundaryTo: Float,
    ): Pair<Float, Float>? {
        val from = max(segmentFrom, boundaryFrom)
        val to = min(segmentTo, boundaryTo)
        return if (to > from) from to to else null
    }

    private fun isHorizontal(wall: WallSegment): Boolean =
        abs(wall.end.z - wall.start.z) <= AXIS_WALL_TOLERANCE_METERS &&
            abs(wall.end.x - wall.start.x) >= MIN_WALL_CONTEXT_METERS

    private fun isVertical(wall: WallSegment): Boolean =
        abs(wall.end.x - wall.start.x) <= AXIS_WALL_TOLERANCE_METERS &&
            abs(wall.end.z - wall.start.z) >= MIN_WALL_CONTEXT_METERS

    private data class AxisSample(val value: Float, val support: Float)

    private data class AxisCluster(var value: Float, var support: Float)

    private data class RoomCandidate(
        val x0: Float,
        val x1: Float,
        val z0: Float,
        val z1: Float,
        val confidence: Float,
    ) {
        val area: Float get() = (x1 - x0) * (z1 - z0)

        fun contains(other: RoomCandidate): Boolean =
            other.x0 >= x0 - ROOM_DUPLICATE_TOLERANCE_METERS &&
                other.x1 <= x1 + ROOM_DUPLICATE_TOLERANCE_METERS &&
                other.z0 >= z0 - ROOM_DUPLICATE_TOLERANCE_METERS &&
                other.z1 <= z1 + ROOM_DUPLICATE_TOLERANCE_METERS

        fun nearlySame(other: RoomCandidate): Boolean =
            abs(x0 - other.x0) <= ROOM_DUPLICATE_TOLERANCE_METERS &&
                abs(x1 - other.x1) <= ROOM_DUPLICATE_TOLERANCE_METERS &&
                abs(z0 - other.z0) <= ROOM_DUPLICATE_TOLERANCE_METERS &&
                abs(z1 - other.z1) <= ROOM_DUPLICATE_TOLERANCE_METERS

        fun stableKey(): String = listOf(x0, x1, z0, z1)
            .joinToString("-") { value -> kotlin.math.round(value * 20f).toInt().toString() }
    }

    private const val AXIS_WALL_TOLERANCE_METERS = 0.08f
    private const val AXIS_CLUSTER_TOLERANCE_METERS = 0.14f
    private const val BOUNDARY_TOLERANCE_METERS = 0.18f
    private const val MERGE_INTERVAL_TOLERANCE_METERS = 0.10f
    private const val MIN_WALL_CONTEXT_METERS = 0.40f
    private const val MIN_ROOM_SPAN_METERS = 0.85f
    private const val MAX_ROOM_SPAN_METERS = 12.0f
    private const val MIN_ROOM_AREA_SQ_METERS = 1.5f
    private const val MAX_ROOM_AREA_SQ_METERS = 95f
    private const val MAX_ROOM_ASPECT_RATIO = 7.5f
    private const val MIN_SIDE_COVERAGE = 0.58f
    private const val MIN_MEAN_COVERAGE = 0.72f
    private const val INTERNAL_DIVIDER_COVERAGE = 0.62f
    private const val INTERNAL_DIVIDER_EDGE_INSET_METERS = 0.34f
    private const val ROOM_DUPLICATE_TOLERANCE_METERS = 0.12f
    private const val ENCLOSURE_AREA_FACTOR = 1.35f
    private const val MAX_AXIS_COORDINATES = 48
    private const val MAX_INFERRED_ROOMS = 64
}
