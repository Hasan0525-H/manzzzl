package com.manzl.app.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry helper for pairing two raster edge segments that plausibly represent opposite
 * physical faces of the same wall. This is deliberately independent from OpenCV so it is covered by
 * JVM regression tests.
 */
internal object WallFacePairing {

    data class PixelLine(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val support: Float = 1f,
    )

    data class Candidate(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val thicknessPx: Float,
        val overlapPx: Float,
        val overlapRatio: Float,
        val angleErrorDegrees: Float,
        val confidence: Float,
    )

    fun pair(
        lines: List<PixelLine>,
        minThicknessPx: Float,
        maxThicknessPx: Float,
        minOverlapPx: Float,
        maxAngleErrorDegrees: Float = DEFAULT_MAX_ANGLE_ERROR_DEGREES,
        minOverlapRatio: Float = DEFAULT_MIN_OVERLAP_RATIO,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): List<Candidate> {
        if (
            lines.size < 2 ||
            minThicknessPx <= 0f ||
            maxThicknessPx < minThicknessPx ||
            minOverlapPx <= 0f ||
            maxResults <= 0
        ) return emptyList()

        val prepared = lines.mapNotNull(::prepare)
        if (prepared.size < 2) return emptyList()
        val output = ArrayList<Candidate>()

        for (i in 0 until prepared.lastIndex) {
            val a = prepared[i]
            for (j in i + 1 until prepared.size) {
                val b = prepared[j]
                val angleError = axisAngleDifferenceDegrees(a.angleDegrees, b.angleDegrees)
                if (angleError > maxAngleErrorDegrees) continue

                val alignedBux = if (a.ux * b.ux + a.uy * b.uy >= 0f) b.ux else -b.ux
                val alignedBuy = if (a.ux * b.ux + a.uy * b.uy >= 0f) b.uy else -b.uy
                val axisX = a.ux + alignedBux
                val axisY = a.uy + alignedBuy
                val axisLength = sqrt(axisX * axisX + axisY * axisY)
                if (axisLength <= EPSILON) continue
                val ux = axisX / axisLength
                val uy = axisY / axisLength
                val nx = -uy
                val ny = ux

                val aOffset = midpoint(a.line).dot(nx, ny)
                val bOffset = midpoint(b.line).dot(nx, ny)
                val separation = abs(aOffset - bOffset)
                if (separation !in minThicknessPx..maxThicknessPx) continue

                val aInterval = interval(a.line, ux, uy)
                val bInterval = interval(b.line, ux, uy)
                val overlapFrom = max(aInterval.min, bInterval.min)
                val overlapTo = min(aInterval.max, bInterval.max)
                val overlap = overlapTo - overlapFrom
                if (overlap < minOverlapPx) continue
                val shorter = min(aInterval.length, bInterval.length).coerceAtLeast(EPSILON)
                val overlapRatio = (overlap / shorter).coerceIn(0f, 1f)
                if (overlapRatio < minOverlapRatio) continue

                // Use the union extent after requiring strong overlap. Junctions often shorten one of the
                // two visible wall faces, so using only the shared interval would systematically shorten
                // reconstructed walls around T/L intersections.
                val from = min(aInterval.min, bInterval.min)
                val to = max(aInterval.max, bInterval.max)
                val centerOffset = (aOffset + bOffset) * 0.5f
                val start = Point(ux * from + nx * centerOffset, uy * from + ny * centerOffset)
                val end = Point(ux * to + nx * centerOffset, uy * to + ny * centerOffset)
                val support = min(a.line.support, b.line.support).coerceIn(0f, 1f)
                val angleScore = (1f - angleError / maxAngleErrorDegrees.coerceAtLeast(0.1f)).coerceIn(0f, 1f)
                val confidence = (
                    support * 0.42f +
                        overlapRatio * 0.42f +
                        angleScore * 0.16f
                    ).coerceIn(0f, 0.99f)

                output += Candidate(
                    x0 = start.x,
                    y0 = start.y,
                    x1 = end.x,
                    y1 = end.y,
                    thicknessPx = separation,
                    overlapPx = overlap,
                    overlapRatio = overlapRatio,
                    angleErrorDegrees = angleError,
                    confidence = confidence,
                )
            }
        }

        return output
            .sortedWith(
                compareByDescending<Candidate> { it.confidence }
                    .thenByDescending { it.overlapPx }
            )
            .fold(ArrayList<Candidate>()) { accepted, candidate ->
                if (accepted.none { duplicate(it, candidate) }) accepted += candidate
                accepted
            }
            .take(maxResults)
    }

    private fun prepare(line: PixelLine): Prepared? {
        val dx = line.x1 - line.x0
        val dy = line.y1 - line.y0
        val length = sqrt(dx * dx + dy * dy)
        if (length < MIN_LINE_LENGTH_PX) return null
        val ux = dx / length
        val uy = dy / length
        val angle = normalizeHalfTurnDegrees(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())
        return Prepared(line, ux, uy, length, angle)
    }

    private fun interval(line: PixelLine, ux: Float, uy: Float): Interval {
        val a = line.x0 * ux + line.y0 * uy
        val b = line.x1 * ux + line.y1 * uy
        return Interval(min(a, b), max(a, b))
    }

    private fun midpoint(line: PixelLine): Point = Point(
        x = (line.x0 + line.x1) * 0.5f,
        y = (line.y0 + line.y1) * 0.5f,
    )

    private fun duplicate(a: Candidate, b: Candidate): Boolean {
        val aAngle = normalizeHalfTurnDegrees(Math.toDegrees(atan2((a.y1 - a.y0).toDouble(), (a.x1 - a.x0).toDouble())).toFloat())
        val bAngle = normalizeHalfTurnDegrees(Math.toDegrees(atan2((b.y1 - b.y0).toDouble(), (b.x1 - b.x0).toDouble())).toFloat())
        if (axisAngleDifferenceDegrees(aAngle, bAngle) > DUPLICATE_ANGLE_DEGREES) return false
        val aMid = Point((a.x0 + a.x1) * 0.5f, (a.y0 + a.y1) * 0.5f)
        val distance = pointSegmentDistance(aMid.x, aMid.y, b.x0, b.y0, b.x1, b.y1)
        return distance <= max(DUPLICATE_LINE_DISTANCE_PX, max(a.thicknessPx, b.thicknessPx) * 0.55f)
    }

    private fun pointSegmentDistance(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Float {
        val vx = bx - ax
        val vy = by - ay
        val lengthSq = vx * vx + vy * vy
        if (lengthSq <= EPSILON) {
            val dx = px - ax
            val dy = py - ay
            return sqrt(dx * dx + dy * dy)
        }
        val t = (((px - ax) * vx + (py - ay) * vy) / lengthSq).coerceIn(0f, 1f)
        val qx = ax + vx * t
        val qy = ay + vy * t
        val dx = px - qx
        val dy = py - qy
        return sqrt(dx * dx + dy * dy)
    }

    private fun axisAngleDifferenceDegrees(a: Float, b: Float): Float {
        val delta = abs(normalizeHalfTurnDegrees(a) - normalizeHalfTurnDegrees(b))
        return min(delta, 180f - delta)
    }

    private fun normalizeHalfTurnDegrees(value: Float): Float {
        var result = value % 180f
        if (result < 0f) result += 180f
        return result
    }

    private fun Point.dot(x: Float, y: Float): Float = this.x * x + this.y * y

    private data class Prepared(
        val line: PixelLine,
        val ux: Float,
        val uy: Float,
        val length: Float,
        val angleDegrees: Float,
    )

    private data class Interval(val min: Float, val max: Float) {
        val length: Float get() = max - min
    }

    private data class Point(val x: Float, val y: Float)

    private const val EPSILON = 0.000001f
    private const val MIN_LINE_LENGTH_PX = 8f
    private const val DEFAULT_MAX_ANGLE_ERROR_DEGREES = 3.2f
    private const val DEFAULT_MIN_OVERLAP_RATIO = 0.48f
    private const val DEFAULT_MAX_RESULTS = 96
    private const val DUPLICATE_ANGLE_DEGREES = 3.5f
    private const val DUPLICATE_LINE_DISTANCE_PX = 3.5f
}
