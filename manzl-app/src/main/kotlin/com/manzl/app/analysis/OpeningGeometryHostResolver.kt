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
 * Resolves the measured wall-gap geometry that is allowed to host a semantic door/window.
 *
 * Semantic evidence is never allowed to punch a new opening through a continuous measured wall.
 * A valid host therefore requires two trusted, nearly-collinear wall runs on opposite sides of the
 * candidate, with a measured gap whose width agrees with the semantic observation. The returned
 * center and rotation come from those wall runs, not from AI/CV coordinates.
 *
 * This works for arbitrary-angle walls; no 0/90 degree snapping is used.
 */
internal object OpeningGeometryHostResolver {

    data class Host(
        val center: Vec2,
        val widthMeters: Float,
        val rotationDegrees: Float,
        val supportConfidence: Float,
    )

    fun resolve(
        walls: List<WallSegment>,
        candidateCenter: Vec2,
        candidateWidthMeters: Float,
        candidateRotationDegrees: Float?,
    ): Host? {
        if (candidateWidthMeters <= 0f || walls.size < 2) return null
        val prepared = walls.mapIndexedNotNull { index, wall -> PreparedWall.from(index, wall) }
        if (prepared.size < 2) return null

        var best: ScoredHost? = null
        for (i in 0 until prepared.lastIndex) {
            val a = prepared[i]
            for (j in i + 1 until prepared.size) {
                val b = prepared[j]
                val alignment = a.ux * b.ux + a.uz * b.uz
                if (abs(alignment) < MIN_COLLINEAR_ALIGNMENT) continue

                val bux = if (alignment >= 0f) b.ux else -b.ux
                val buz = if (alignment >= 0f) b.uz else -b.uz
                val ux = normalizedComponent(a.ux + bux, a.uz + buz, x = true) ?: continue
                val uz = normalizedComponent(a.ux + bux, a.uz + buz, x = false) ?: continue
                val nx = -uz
                val nz = ux

                val aMid = midpoint(a.wall)
                val bMid = midpoint(b.wall)
                val lineSeparation = abs((bMid.x - aMid.x) * nx + (bMid.z - aMid.z) * nz)
                if (lineSeparation > MAX_COLLINEAR_OFFSET_METERS) continue

                val axisAngle = normalizeHalfTurnDegrees(
                    Math.toDegrees(atan2(uz.toDouble(), ux.toDouble())).toFloat(),
                )
                if (
                    candidateRotationDegrees != null &&
                    axisAngleDifference(candidateRotationDegrees, axisAngle) > MAX_EVIDENCE_ANGLE_ERROR_DEGREES
                ) continue

                val candidatePerpendicular = abs(
                    (candidateCenter.x - aMid.x) * nx + (candidateCenter.z - aMid.z) * nz,
                )
                if (candidatePerpendicular > MAX_CANDIDATE_LINE_DISTANCE_METERS) continue

                val aInterval = intervalAlongAxis(a.wall, candidateCenter, ux, uz)
                val bInterval = intervalAlongAxis(b.wall, candidateCenter, ux, uz)
                val ordered = orderOppositeIntervals(aInterval, bInterval) ?: continue
                val left = ordered.first
                val right = ordered.second
                if (left.max > CENTER_SIDE_TOLERANCE_METERS) continue
                if (right.min < -CENTER_SIDE_TOLERANCE_METERS) continue

                val gap = right.min - left.max
                if (gap !in MIN_MEASURED_OPENING_GAP_METERS..MAX_MEASURED_OPENING_GAP_METERS) continue
                val widthTolerance = max(MIN_WIDTH_TOLERANCE_METERS, gap * WIDTH_TOLERANCE_RATIO)
                if (abs(gap - candidateWidthMeters) > widthTolerance) continue

                val leftPoint = Vec2(
                    candidateCenter.x + ux * left.max,
                    candidateCenter.z + uz * left.max,
                )
                val rightPoint = Vec2(
                    candidateCenter.x + ux * right.min,
                    candidateCenter.z + uz * right.min,
                )
                val gapCenter = Vec2(
                    (leftPoint.x + rightPoint.x) * 0.5f,
                    (leftPoint.z + rightPoint.z) * 0.5f,
                )
                val supportConfidence = min(a.wall.confidence, b.wall.confidence).coerceIn(0f, 1f)
                val score =
                    abs(gap - candidateWidthMeters) * 1.45f +
                        lineSeparation * 1.20f +
                        candidatePerpendicular * 0.85f +
                        (1f - supportConfidence) * 0.30f
                val host = Host(
                    center = gapCenter,
                    widthMeters = gap,
                    rotationDegrees = axisAngle,
                    supportConfidence = supportConfidence,
                )
                if (best == null || score < best.score) best = ScoredHost(host, score)
            }
        }
        return best?.host
    }

    private fun orderOppositeIntervals(a: AxisInterval, b: AxisInterval): Pair<AxisInterval, AxisInterval>? {
        val aLeft = a.max <= CENTER_SIDE_TOLERANCE_METERS
        val aRight = a.min >= -CENTER_SIDE_TOLERANCE_METERS
        val bLeft = b.max <= CENTER_SIDE_TOLERANCE_METERS
        val bRight = b.min >= -CENTER_SIDE_TOLERANCE_METERS
        return when {
            aLeft && bRight -> a to b
            bLeft && aRight -> b to a
            else -> null
        }
    }

    private fun intervalAlongAxis(wall: WallSegment, origin: Vec2, ux: Float, uz: Float): AxisInterval {
        val p0 = (wall.start.x - origin.x) * ux + (wall.start.z - origin.z) * uz
        val p1 = (wall.end.x - origin.x) * ux + (wall.end.z - origin.z) * uz
        return AxisInterval(min(p0, p1), max(p0, p1))
    }

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

    private fun normalizedComponent(x: Float, z: Float, xComponent: Boolean? = null, x: Boolean): Float? {
        val length = sqrt(x * x + z * z)
        if (length <= 0.000001f) return null
        return if (x) x / length else z / length
    }

    private fun midpoint(wall: WallSegment): Vec2 = Vec2(
        (wall.start.x + wall.end.x) * 0.5f,
        (wall.start.z + wall.end.z) * 0.5f,
    )

    private data class PreparedWall(
        val index: Int,
        val wall: WallSegment,
        val ux: Float,
        val uz: Float,
    ) {
        companion object {
            fun from(index: Int, wall: WallSegment): PreparedWall? {
                val dx = wall.end.x - wall.start.x
                val dz = wall.end.z - wall.start.z
                val length = sqrt(dx * dx + dz * dz)
                if (length < MIN_SUPPORT_WALL_LENGTH_METERS || wall.confidence < MIN_SUPPORT_CONFIDENCE) {
                    return null
                }
                return PreparedWall(index, wall, dx / length, dz / length)
            }
        }
    }

    private data class AxisInterval(val min: Float, val max: Float)
    private data class ScoredHost(val host: Host, val score: Float)

    private const val MIN_SUPPORT_WALL_LENGTH_METERS = 0.30f
    private const val MIN_SUPPORT_CONFIDENCE = 0.56f
    private val MIN_COLLINEAR_ALIGNMENT = cos(8.0 * PI / 180.0).toFloat()
    private const val MAX_COLLINEAR_OFFSET_METERS = 0.18f
    private const val MAX_CANDIDATE_LINE_DISTANCE_METERS = 0.30f
    private const val MAX_EVIDENCE_ANGLE_ERROR_DEGREES = 24f
    private const val CENTER_SIDE_TOLERANCE_METERS = 0.10f
    private const val MIN_MEASURED_OPENING_GAP_METERS = 0.38f
    private const val MAX_MEASURED_OPENING_GAP_METERS = 4.35f
    private const val MIN_WIDTH_TOLERANCE_METERS = 0.20f
    private const val WIDTH_TOLERANCE_RATIO = 0.26f
}
