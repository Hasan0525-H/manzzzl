package com.manzl.app.analysis

import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Enumerates opening-sized gaps that already exist in measured wall geometry.
 *
 * This is proposal generation only. It does not decide whether a gap is a door or window and it
 * never changes wall topology. The detector works in the wall's own axis, so diagonal and arbitrary
 * angle walls receive the same treatment as horizontal/vertical walls.
 */
internal object MeasuredOpeningGapDetector {

    data class Gap(
        val center: Vec2,
        val widthMeters: Float,
        val rotationDegrees: Float,
        val supportConfidence: Float,
    )

    fun detect(
        walls: List<WallSegment>,
        minWidthMeters: Float = DEFAULT_MIN_GAP_METERS,
        maxWidthMeters: Float = DEFAULT_MAX_GAP_METERS,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): List<Gap> {
        if (
            walls.size < 2 ||
            minWidthMeters <= 0f ||
            maxWidthMeters < minWidthMeters ||
            maxResults <= 0
        ) return emptyList()

        val prepared = walls.mapNotNull(::prepare)
        if (prepared.size < 2) return emptyList()
        val candidates = ArrayList<Gap>()

        for (i in 0 until prepared.lastIndex) {
            val a = prepared[i]
            for (j in i + 1 until prepared.size) {
                val b = prepared[j]
                val alignment = a.ux * b.ux + a.uz * b.uz
                if (abs(alignment) < MIN_COLLINEAR_ALIGNMENT) continue

                val alignedBux = if (alignment >= 0f) b.ux else -b.ux
                val alignedBuz = if (alignment >= 0f) b.uz else -b.uz
                val axisX = a.ux + alignedBux
                val axisZ = a.uz + alignedBuz
                val axisLength = sqrt(axisX * axisX + axisZ * axisZ)
                if (axisLength <= EPSILON) continue
                val ux = axisX / axisLength
                val uz = axisZ / axisLength
                val nx = -uz
                val nz = ux

                val aMid = midpoint(a.wall)
                val bMid = midpoint(b.wall)
                val aNormal = aMid.x * nx + aMid.z * nz
                val bNormal = bMid.x * nx + bMid.z * nz
                if (abs(aNormal - bNormal) > MAX_LINE_SEPARATION_METERS) continue

                val aInterval = interval(a.wall, ux, uz)
                val bInterval = interval(b.wall, ux, uz)
                val ordered = when {
                    aInterval.max < bInterval.min -> aInterval to bInterval
                    bInterval.max < aInterval.min -> bInterval to aInterval
                    else -> continue
                }
                val left = ordered.first
                val right = ordered.second
                val gap = right.min - left.max
                if (gap !in minWidthMeters..maxWidthMeters) continue

                val centerAlong = (left.max + right.min) * 0.5f
                val centerNormal = (aNormal + bNormal) * 0.5f
                val center = Vec2(
                    x = ux * centerAlong + nx * centerNormal,
                    z = uz * centerAlong + nz * centerNormal,
                )
                val rotation = normalizeHalfTurnDegrees(
                    Math.toDegrees(atan2(uz.toDouble(), ux.toDouble())).toFloat(),
                )
                candidates += Gap(
                    center = center,
                    widthMeters = gap,
                    rotationDegrees = rotation,
                    supportConfidence = min(a.wall.confidence, b.wall.confidence).coerceIn(0f, 1f),
                )
            }
        }

        return candidates
            .sortedWith(
                compareByDescending<Gap> { it.supportConfidence }
                    .thenBy { it.widthMeters }
            )
            .fold(ArrayList()) { accepted, candidate ->
                val duplicate = accepted.any { existing -> sameGap(existing, candidate) }
                if (!duplicate) accepted += candidate
                accepted
            }
            .take(maxResults)
    }

    private fun sameGap(a: Gap, b: Gap): Boolean {
        val dx = a.center.x - b.center.x
        val dz = a.center.z - b.center.z
        val centerDistance = sqrt(dx * dx + dz * dz)
        if (centerDistance > DUPLICATE_CENTER_METERS) return false
        val angleDelta = axisAngleDifference(a.rotationDegrees, b.rotationDegrees)
        return angleDelta <= DUPLICATE_ANGLE_DEGREES &&
            abs(a.widthMeters - b.widthMeters) <= DUPLICATE_WIDTH_METERS
    }

    private fun prepare(wall: WallSegment): PreparedWall? {
        val dx = wall.end.x - wall.start.x
        val dz = wall.end.z - wall.start.z
        val length = sqrt(dx * dx + dz * dz)
        if (length < MIN_SUPPORT_WALL_LENGTH_METERS || wall.confidence < MIN_SUPPORT_CONFIDENCE) {
            return null
        }
        return PreparedWall(wall, dx / length, dz / length)
    }

    private fun interval(wall: WallSegment, ux: Float, uz: Float): AxisInterval {
        val a = wall.start.x * ux + wall.start.z * uz
        val b = wall.end.x * ux + wall.end.z * uz
        return AxisInterval(min(a, b), max(a, b))
    }

    private fun midpoint(wall: WallSegment) = Vec2(
        (wall.start.x + wall.end.x) * 0.5f,
        (wall.start.z + wall.end.z) * 0.5f,
    )

    private fun axisAngleDifference(a: Float, b: Float): Float {
        val na = normalizeHalfTurnDegrees(a)
        val nb = normalizeHalfTurnDegrees(b)
        val delta = abs(na - nb)
        return min(delta, 180f - delta)
    }

    private fun normalizeHalfTurnDegrees(value: Float): Float {
        var result = value % 180f
        if (result < 0f) result += 180f
        return result
    }

    private data class PreparedWall(
        val wall: WallSegment,
        val ux: Float,
        val uz: Float,
    )

    private data class AxisInterval(val min: Float, val max: Float)

    private const val EPSILON = 0.000001f
    private const val MIN_SUPPORT_WALL_LENGTH_METERS = 0.30f
    private const val MIN_SUPPORT_CONFIDENCE = 0.56f
    private val MIN_COLLINEAR_ALIGNMENT = cos(8.0 * PI / 180.0).toFloat()
    private const val MAX_LINE_SEPARATION_METERS = 0.18f
    private const val DEFAULT_MIN_GAP_METERS = 0.38f
    private const val DEFAULT_MAX_GAP_METERS = 4.35f
    private const val DEFAULT_MAX_RESULTS = 64
    private const val DUPLICATE_CENTER_METERS = 0.24f
    private const val DUPLICATE_ANGLE_DEGREES = 8f
    private const val DUPLICATE_WIDTH_METERS = 0.24f
}
