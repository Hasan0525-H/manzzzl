package com.manzl.app.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Converts the student model's dense wall/corner/orientation heads into arbitrary-angle vector wall
 * proposals. The decoder is pure Kotlin and deterministic so its geometry can be regression-tested
 * without ONNX/OpenCV. Output remains proposal-only and must still pass source-raster adjudication.
 */
internal object StudentWallGeometryDecoder {

    data class Segment(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val confidence: Float,
    )

    fun decode(
        wallMask: BooleanArray,
        cornerProbability: FloatArray,
        orientationX: FloatArray,
        orientationY: FloatArray,
        side: Int,
        minLengthPx: Float = DEFAULT_MIN_LENGTH_PX,
        maxSegments: Int = DEFAULT_MAX_SEGMENTS,
    ): List<Segment> {
        val plane = side * side
        if (
            side < 16 ||
            wallMask.size != plane ||
            cornerProbability.size != plane ||
            orientationX.size != plane ||
            orientationY.size != plane ||
            minLengthPx <= 0f ||
            maxSegments <= 0
        ) return emptyList()

        val visited = BooleanArray(plane)
        val proposals = ArrayList<Segment>()
        var y = TRACE_MARGIN
        while (y < side - TRACE_MARGIN) {
            var x = TRACE_MARGIN
            while (x < side - TRACE_MARGIN) {
                val index = y * side + x
                if (wallMask[index] && !visited[index]) {
                    traceFrom(
                        seedX = x.toFloat(),
                        seedY = y.toFloat(),
                        wallMask = wallMask,
                        cornerProbability = cornerProbability,
                        orientationX = orientationX,
                        orientationY = orientationY,
                        side = side,
                        visited = visited,
                        minLengthPx = minLengthPx,
                    )?.let(proposals::add)
                }
                x += SEED_STRIDE
            }
            y += SEED_STRIDE
        }

        return proposals
            .sortedByDescending { it.confidence * segmentLength(it) }
            .fold(ArrayList<Segment>()) { accepted, candidate ->
                val duplicate = accepted.any { existing -> sameAxisSegment(existing, candidate) }
                if (!duplicate) accepted += candidate
                accepted
            }
            .take(maxSegments)
    }

    private fun traceFrom(
        seedX: Float,
        seedY: Float,
        wallMask: BooleanArray,
        cornerProbability: FloatArray,
        orientationX: FloatArray,
        orientationY: FloatArray,
        side: Int,
        visited: BooleanArray,
        minLengthPx: Float,
    ): Segment? {
        val seedIndex = seedY.toInt() * side + seedX.toInt()
        val initial = normalizedDirection(orientationX[seedIndex], orientationY[seedIndex]) ?: return null
        val forward = traceDirection(
            seedX, seedY, initial.first, initial.second, wallMask, orientationX, orientationY, side, visited
        )
        val backward = traceDirection(
            seedX, seedY, -initial.first, -initial.second, wallMask, orientationX, orientationY, side, visited
        )

        var start = Point(backward.x, backward.y)
        var end = Point(forward.x, forward.y)
        val length = distance(start, end)
        if (length < minLengthPx) return null

        start = snapToCorner(start, cornerProbability, side)
        end = snapToCorner(end, cornerProbability, side)
        val snappedLength = distance(start, end)
        if (snappedLength < minLengthPx) return null

        val support = lineSupport(start, end, wallMask, side)
        if (support < MIN_LINE_SUPPORT) return null
        val cornerSupport = (cornerScore(start, cornerProbability, side) + cornerScore(end, cornerProbability, side)) * 0.5f
        val confidence = (
            support * 0.68f +
                cornerSupport * 0.22f +
                (snappedLength / LONG_SEGMENT_PX).coerceIn(0f, 1f) * 0.10f
            ).coerceIn(0f, 0.98f)
        if (confidence < MIN_OUTPUT_CONFIDENCE) return null

        markVisitedNearLine(start, end, visited, side)
        return Segment(start.x, start.y, end.x, end.y, confidence)
    }

    private fun traceDirection(
        seedX: Float,
        seedY: Float,
        directionX: Float,
        directionY: Float,
        wallMask: BooleanArray,
        orientationX: FloatArray,
        orientationY: FloatArray,
        side: Int,
        visited: BooleanArray,
    ): Point {
        var x = seedX
        var y = seedY
        var ux = directionX
        var uy = directionY
        var misses = 0
        var steps = 0
        var lastGood = Point(x, y)

        while (steps++ < MAX_TRACE_STEPS) {
            x += ux * TRACE_STEP_PX
            y += uy * TRACE_STEP_PX
            if (x < TRACE_MARGIN || y < TRACE_MARGIN || x >= side - TRACE_MARGIN || y >= side - TRACE_MARGIN) break

            val supportPoint = nearestWallPixel(x, y, wallMask, side, TRACE_SEARCH_RADIUS)
            if (supportPoint == null) {
                misses++
                if (misses > MAX_CONSECUTIVE_MISSES) break
                continue
            }
            misses = 0
            x = supportPoint.x
            y = supportPoint.y
            lastGood = supportPoint
            val index = supportPoint.y.toInt() * side + supportPoint.x.toInt()
            visited[index] = true

            val local = normalizedDirection(orientationX[index], orientationY[index])
            if (local != null) {
                var lx = local.first
                var ly = local.second
                if (lx * ux + ly * uy < 0f) {
                    lx = -lx
                    ly = -ly
                }
                val alignment = lx * ux + ly * uy
                if (alignment >= MIN_ORIENTATION_ALIGNMENT) {
                    ux = ux * ORIENTATION_INERTIA + lx * (1f - ORIENTATION_INERTIA)
                    uy = uy * ORIENTATION_INERTIA + ly * (1f - ORIENTATION_INERTIA)
                    val normalized = normalizedDirection(ux, uy)
                    if (normalized != null) {
                        ux = normalized.first
                        uy = normalized.second
                    }
                }
            }
        }
        return lastGood
    }

    private fun nearestWallPixel(
        x: Float,
        y: Float,
        wallMask: BooleanArray,
        side: Int,
        radius: Int,
    ): Point? {
        val cx = x.toInt()
        val cy = y.toInt()
        var best: Point? = null
        var bestDistanceSq = Float.POSITIVE_INFINITY
        for (dy in -radius..radius) {
            val py = cy + dy
            if (py !in 0 until side) continue
            for (dx in -radius..radius) {
                val px = cx + dx
                if (px !in 0 until side || !wallMask[py * side + px]) continue
                val ddx = px - x
                val ddy = py - y
                val d2 = ddx * ddx + ddy * ddy
                if (d2 < bestDistanceSq) {
                    bestDistanceSq = d2
                    best = Point(px.toFloat(), py.toFloat())
                }
            }
        }
        return best
    }

    private fun snapToCorner(point: Point, corners: FloatArray, side: Int): Point {
        val cx = point.x.toInt()
        val cy = point.y.toInt()
        var best = point
        var bestScore = MIN_CORNER_SNAP_PROBABILITY
        var bestDistanceSq = Float.POSITIVE_INFINITY
        for (dy in -CORNER_SNAP_RADIUS..CORNER_SNAP_RADIUS) {
            val py = cy + dy
            if (py !in 0 until side) continue
            for (dx in -CORNER_SNAP_RADIUS..CORNER_SNAP_RADIUS) {
                val px = cx + dx
                if (px !in 0 until side) continue
                val score = corners[py * side + px]
                if (score < bestScore) continue
                val d2 = (px - point.x) * (px - point.x) + (py - point.y) * (py - point.y)
                if (score > bestScore + 0.02f || (abs(score - bestScore) <= 0.02f && d2 < bestDistanceSq)) {
                    bestScore = score
                    bestDistanceSq = d2
                    best = Point(px.toFloat(), py.toFloat())
                }
            }
        }
        return best
    }

    private fun cornerScore(point: Point, corners: FloatArray, side: Int): Float {
        val x = point.x.toInt().coerceIn(0, side - 1)
        val y = point.y.toInt().coerceIn(0, side - 1)
        return corners[y * side + x].coerceIn(0f, 1f)
    }

    private fun lineSupport(start: Point, end: Point, wallMask: BooleanArray, side: Int): Float {
        val length = distance(start, end)
        val samples = max(MIN_SUPPORT_SAMPLES, (length / SUPPORT_SAMPLE_SPACING).toInt())
        var supported = 0
        for (index in 0 until samples) {
            val t = if (samples == 1) 0.5f else index / (samples - 1f)
            val x = start.x + (end.x - start.x) * t
            val y = start.y + (end.y - start.y) * t
            if (nearestWallPixel(x, y, wallMask, side, SUPPORT_RADIUS) != null) supported++
        }
        return supported / samples.toFloat()
    }

    private fun markVisitedNearLine(start: Point, end: Point, visited: BooleanArray, side: Int) {
        val length = distance(start, end)
        val samples = max(2, (length / VISIT_SAMPLE_SPACING).toInt())
        for (index in 0 until samples) {
            val t = index / (samples - 1f)
            val x = (start.x + (end.x - start.x) * t).toInt()
            val y = (start.y + (end.y - start.y) * t).toInt()
            for (dy in -VISIT_RADIUS..VISIT_RADIUS) {
                val py = y + dy
                if (py !in 0 until side) continue
                for (dx in -VISIT_RADIUS..VISIT_RADIUS) {
                    val px = x + dx
                    if (px in 0 until side) visited[py * side + px] = true
                }
            }
        }
    }

    private fun sameAxisSegment(a: Segment, b: Segment): Boolean {
        val adx = a.x1 - a.x0
        val ady = a.y1 - a.y0
        val bdx = b.x1 - b.x0
        val bdy = b.y1 - b.y0
        val al = sqrt(adx * adx + ady * ady)
        val bl = sqrt(bdx * bdx + bdy * bdy)
        if (al <= EPSILON || bl <= EPSILON) return false
        val alignment = abs((adx / al) * (bdx / bl) + (ady / al) * (bdy / bl))
        if (alignment < DUPLICATE_ALIGNMENT) return false
        val midX = (b.x0 + b.x1) * 0.5f
        val midY = (b.y0 + b.y1) * 0.5f
        return pointSegmentDistance(midX, midY, a.x0, a.y0, a.x1, a.y1) <= DUPLICATE_DISTANCE_PX
    }

    private fun pointSegmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
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

    private fun normalizedDirection(x: Float, y: Float): Pair<Float, Float>? {
        val length = sqrt(x * x + y * y)
        if (!length.isFinite() || length < MIN_ORIENTATION_NORM) return null
        return x / length to y / length
    }

    private fun distance(a: Point, b: Point): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun segmentLength(segment: Segment): Float =
        distance(Point(segment.x0, segment.y0), Point(segment.x1, segment.y1))

    private data class Point(val x: Float, val y: Float)

    private const val EPSILON = 0.000001f
    private const val DEFAULT_MIN_LENGTH_PX = 18f
    private const val DEFAULT_MAX_SEGMENTS = 128
    private const val TRACE_MARGIN = 2
    private const val SEED_STRIDE = 3
    private const val TRACE_STEP_PX = 2.0f
    private const val TRACE_SEARCH_RADIUS = 2
    private const val MAX_CONSECUTIVE_MISSES = 2
    private const val MAX_TRACE_STEPS = 400
    private const val MIN_ORIENTATION_NORM = 0.25f
    private const val MIN_ORIENTATION_ALIGNMENT = 0.72f
    private const val ORIENTATION_INERTIA = 0.72f
    private const val CORNER_SNAP_RADIUS = 7
    private const val MIN_CORNER_SNAP_PROBABILITY = 0.56f
    private const val MIN_SUPPORT_SAMPLES = 10
    private const val SUPPORT_SAMPLE_SPACING = 3.0f
    private const val SUPPORT_RADIUS = 2
    private const val MIN_LINE_SUPPORT = 0.78f
    private const val LONG_SEGMENT_PX = 120f
    private const val MIN_OUTPUT_CONFIDENCE = 0.70f
    private const val VISIT_SAMPLE_SPACING = 3.0f
    private const val VISIT_RADIUS = 2
    private const val DUPLICATE_ALIGNMENT = 0.985f
    private const val DUPLICATE_DISTANCE_PX = 5.0f
}
