package com.manzl.app.render

import com.manzl.app.model.Vec2
import kotlin.math.abs

internal data class Triangle2(
    val a: Vec2,
    val b: Vec2,
    val c: Vec2,
)

/**
 * Deterministic ear-clipping triangulation for simple room polygons.
 *
 * Room inference may produce L/U-shaped spaces that cannot safely be approximated by one bounding
 * rectangle. This helper preserves the measured polygon and returns triangles only when the shape
 * can be validated as a non-degenerate simple contour. Invalid/ambiguous contours fail closed.
 */
internal object PolygonTriangulator {

    fun triangulate(source: List<Vec2>): List<Triangle2> {
        val points = sanitize(source)
        if (points.size < 3) return emptyList()

        val signedArea = signedArea(points)
        if (abs(signedArea) < MIN_POLYGON_AREA) return emptyList()
        val counterClockwise = signedArea > 0f

        val remaining = points.indices.toMutableList()
        val triangles = ArrayList<Triangle2>(points.size - 2)
        var guard = 0

        while (remaining.size > 3 && guard < points.size * points.size) {
            var clipped = false
            for (position in remaining.indices) {
                val previousIndex = remaining[(position - 1 + remaining.size) % remaining.size]
                val currentIndex = remaining[position]
                val nextIndex = remaining[(position + 1) % remaining.size]
                val a = points[previousIndex]
                val b = points[currentIndex]
                val c = points[nextIndex]

                val turn = cross(a, b, c)
                val convex = if (counterClockwise) turn > EPSILON else turn < -EPSILON
                if (!convex) continue

                val containsOtherPoint = remaining.any { candidateIndex ->
                    candidateIndex != previousIndex &&
                        candidateIndex != currentIndex &&
                        candidateIndex != nextIndex &&
                        pointInsideOrOnTriangle(points[candidateIndex], a, b, c)
                }
                if (containsOtherPoint) continue

                triangles += Triangle2(a, b, c)
                remaining.removeAt(position)
                clipped = true
                break
            }

            if (!clipped) return emptyList()
            guard++
        }

        if (remaining.size != 3) return emptyList()
        val a = points[remaining[0]]
        val b = points[remaining[1]]
        val c = points[remaining[2]]
        if (abs(cross(a, b, c)) <= EPSILON) return emptyList()
        triangles += Triangle2(a, b, c)

        // For a valid simple polygon, ear clipping must always produce exactly n - 2 triangles.
        return if (triangles.size == points.size - 2) triangles else emptyList()
    }

    internal fun polygonArea(points: List<Vec2>): Float = abs(signedArea(points))

    private fun sanitize(source: List<Vec2>): List<Vec2> {
        if (source.isEmpty()) return emptyList()
        val deduplicated = ArrayList<Vec2>(source.size)
        for (point in source) {
            if (deduplicated.isEmpty() || squaredDistance(deduplicated.last(), point) > DUPLICATE_DISTANCE_SQ) {
                deduplicated += point
            }
        }
        if (deduplicated.size > 1 && squaredDistance(deduplicated.first(), deduplicated.last()) <= DUPLICATE_DISTANCE_SQ) {
            deduplicated.removeAt(deduplicated.lastIndex)
        }

        if (deduplicated.size < 3) return emptyList()
        val result = deduplicated.toMutableList()
        var changed = true
        var passes = 0
        while (changed && result.size > 3 && passes < source.size) {
            changed = false
            for (index in result.indices) {
                val previous = result[(index - 1 + result.size) % result.size]
                val current = result[index]
                val next = result[(index + 1) % result.size]
                if (abs(cross(previous, current, next)) <= COLLINEAR_EPSILON &&
                    liesBetween(previous, current, next)
                ) {
                    result.removeAt(index)
                    changed = true
                    break
                }
            }
            passes++
        }
        return result
    }

    private fun signedArea(points: List<Vec2>): Float {
        if (points.size < 3) return 0f
        var twiceArea = 0f
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            twiceArea += a.x * b.z - b.x * a.z
        }
        return twiceArea * 0.5f
    }

    private fun cross(a: Vec2, b: Vec2, c: Vec2): Float =
        (b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x)

    private fun pointInsideOrOnTriangle(point: Vec2, a: Vec2, b: Vec2, c: Vec2): Boolean {
        val ab = cross(a, b, point)
        val bc = cross(b, c, point)
        val ca = cross(c, a, point)
        val hasNegative = ab < -EPSILON || bc < -EPSILON || ca < -EPSILON
        val hasPositive = ab > EPSILON || bc > EPSILON || ca > EPSILON
        return !(hasNegative && hasPositive)
    }

    private fun liesBetween(a: Vec2, b: Vec2, c: Vec2): Boolean {
        val dot = (b.x - a.x) * (b.x - c.x) + (b.z - a.z) * (b.z - c.z)
        return dot <= EPSILON
    }

    private fun squaredDistance(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return dx * dx + dz * dz
    }

    private const val EPSILON = 0.00001f
    private const val COLLINEAR_EPSILON = 0.00010f
    private const val DUPLICATE_DISTANCE_SQ = 0.0001f * 0.0001f
    private const val MIN_POLYGON_AREA = 0.01f
}
