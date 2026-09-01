package com.manzl.app.render

import com.manzl.app.model.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Conservative polygon-with-holes triangulation for measured floor/ceiling surfaces.
 *
 * The existing ear clipper remains the authority for the outer simple polygon. Holes are then
 * subtracted by adaptive triangle refinement. Only triangles touching a hole boundary are subdivided,
 * so complexity scales with hole perimeter rather than whole-room area. At the final refinement level
 * the geometric error is bounded to roughly [TARGET_BOUNDARY_EDGE_METERS].
 *
 * This avoids the old failure mode where a nested shaft/lightwell had to block the entire 3D build or
 * be silently filled by a room polygon. Invalid holes fail closed: no surface is emitted.
 */
internal object PolygonHoleTriangulator {

    fun triangulate(
        outer: List<Vec2>,
        holes: List<List<Vec2>>,
    ): List<Triangle2> {
        val base = PolygonTriangulator.triangulate(outer)
        if (base.isEmpty()) return emptyList()
        if (holes.isEmpty()) return base

        val validHoles = holes.map { sanitize(it) }
        if (validHoles.any { it.size < 3 || polygonArea(it) < MIN_HOLE_AREA_SQ_METERS }) return emptyList()
        if (validHoles.any { hole -> !holeStrictlyInsideOuter(hole, outer) }) return emptyList()

        var triangles = base
        for (hole in validHoles) {
            val next = ArrayList<Triangle2>()
            for (triangle in triangles) {
                subtractHole(triangle, hole, depth = 0, output = next)
            }
            triangles = next
            if (triangles.isEmpty()) return emptyList()
        }
        return triangles
    }

    private fun subtractHole(
        triangle: Triangle2,
        hole: List<Vec2>,
        depth: Int,
        output: MutableList<Triangle2>,
    ) {
        when (classify(triangle, hole)) {
            Relation.OUTSIDE -> output += triangle
            Relation.INSIDE -> Unit
            Relation.CROSSES_BOUNDARY -> {
                if (depth >= MAX_REFINEMENT_DEPTH || maxEdgeLength(triangle) <= TARGET_BOUNDARY_EDGE_METERS) {
                    val centroid = Vec2(
                        x = (triangle.a.x + triangle.b.x + triangle.c.x) / 3f,
                        z = (triangle.a.z + triangle.b.z + triangle.c.z) / 3f,
                    )
                    if (!pointInsideOrOnPolygon(centroid, hole)) output += triangle
                    return
                }

                val ab = midpoint(triangle.a, triangle.b)
                val bc = midpoint(triangle.b, triangle.c)
                val ca = midpoint(triangle.c, triangle.a)
                subtractHole(Triangle2(triangle.a, ab, ca), hole, depth + 1, output)
                subtractHole(Triangle2(ab, triangle.b, bc), hole, depth + 1, output)
                subtractHole(Triangle2(ca, bc, triangle.c), hole, depth + 1, output)
                subtractHole(Triangle2(ab, bc, ca), hole, depth + 1, output)
            }
        }
    }

    private fun classify(triangle: Triangle2, hole: List<Vec2>): Relation {
        val vertices = listOf(triangle.a, triangle.b, triangle.c)
        val insideCount = vertices.count { pointInsideOrOnPolygon(it, hole) }
        if (insideCount == 3 && !triangleEdgesCrossPolygon(triangle, hole)) return Relation.INSIDE
        if (insideCount > 0) return Relation.CROSSES_BOUNDARY

        if (triangleEdgesCrossPolygon(triangle, hole)) return Relation.CROSSES_BOUNDARY
        if (hole.any { pointInsideOrOnTriangle(it, triangle) }) return Relation.CROSSES_BOUNDARY
        return Relation.OUTSIDE
    }

    private fun holeStrictlyInsideOuter(hole: List<Vec2>, outer: List<Vec2>): Boolean {
        if (outer.size < 3) return false
        if (hole.any { !pointInsideOrOnPolygon(it, outer) }) return false
        for (index in hole.indices) {
            val a = hole[index]
            val b = hole[(index + 1) % hole.size]
            for (outerIndex in outer.indices) {
                val c = outer[outerIndex]
                val d = outer[(outerIndex + 1) % outer.size]
                if (segmentsIntersect(a, b, c, d)) return false
            }
        }
        return true
    }

    private fun triangleEdgesCrossPolygon(triangle: Triangle2, polygon: List<Vec2>): Boolean {
        val triangleEdges = listOf(
            triangle.a to triangle.b,
            triangle.b to triangle.c,
            triangle.c to triangle.a,
        )
        for ((a, b) in triangleEdges) {
            for (index in polygon.indices) {
                val c = polygon[index]
                val d = polygon[(index + 1) % polygon.size]
                if (segmentsIntersect(a, b, c, d)) return true
            }
        }
        return false
    }

    private fun pointInsideOrOnTriangle(point: Vec2, triangle: Triangle2): Boolean {
        val c1 = cross(triangle.a, triangle.b, point)
        val c2 = cross(triangle.b, triangle.c, point)
        val c3 = cross(triangle.c, triangle.a, point)
        val hasNegative = c1 < -EPSILON || c2 < -EPSILON || c3 < -EPSILON
        val hasPositive = c1 > EPSILON || c2 > EPSILON || c3 > EPSILON
        return !(hasNegative && hasPositive)
    }

    private fun pointInsideOrOnPolygon(point: Vec2, polygon: List<Vec2>): Boolean {
        if (polygon.size < 3) return false
        for (index in polygon.indices) {
            if (pointSegmentDistance(point, polygon[index], polygon[(index + 1) % polygon.size]) <= EPSILON) {
                return true
            }
        }

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

    private fun segmentsIntersect(a: Vec2, b: Vec2, c: Vec2, d: Vec2): Boolean {
        val o1 = cross(a, b, c)
        val o2 = cross(a, b, d)
        val o3 = cross(c, d, a)
        val o4 = cross(c, d, b)

        if (((o1 > EPSILON && o2 < -EPSILON) || (o1 < -EPSILON && o2 > EPSILON)) &&
            ((o3 > EPSILON && o4 < -EPSILON) || (o3 < -EPSILON && o4 > EPSILON))
        ) return true

        if (abs(o1) <= EPSILON && pointOnSegment(c, a, b)) return true
        if (abs(o2) <= EPSILON && pointOnSegment(d, a, b)) return true
        if (abs(o3) <= EPSILON && pointOnSegment(a, c, d)) return true
        if (abs(o4) <= EPSILON && pointOnSegment(b, c, d)) return true
        return false
    }

    private fun pointOnSegment(point: Vec2, a: Vec2, b: Vec2): Boolean =
        point.x >= minOf(a.x, b.x) - EPSILON &&
            point.x <= maxOf(a.x, b.x) + EPSILON &&
            point.z >= minOf(a.z, b.z) - EPSILON &&
            point.z <= maxOf(a.z, b.z) + EPSILON

    private fun pointSegmentDistance(point: Vec2, a: Vec2, b: Vec2): Float {
        val vx = b.x - a.x
        val vz = b.z - a.z
        val lengthSq = vx * vx + vz * vz
        if (lengthSq <= EPSILON * EPSILON) return distance(point, a)
        val t = (((point.x - a.x) * vx + (point.z - a.z) * vz) / lengthSq).coerceIn(0f, 1f)
        return distance(point, Vec2(a.x + vx * t, a.z + vz * t))
    }

    private fun maxEdgeLength(triangle: Triangle2): Float = max(
        distance(triangle.a, triangle.b),
        max(distance(triangle.b, triangle.c), distance(triangle.c, triangle.a)),
    )

    private fun midpoint(a: Vec2, b: Vec2) = Vec2((a.x + b.x) * 0.5f, (a.z + b.z) * 0.5f)

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun cross(a: Vec2, b: Vec2, c: Vec2): Float =
        (b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x)

    private fun polygonArea(points: List<Vec2>): Float {
        var sum = 0f
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            sum += a.x * b.z - b.x * a.z
        }
        return abs(sum) * 0.5f
    }

    private fun sanitize(source: List<Vec2>): List<Vec2> {
        if (source.isEmpty()) return emptyList()
        val result = ArrayList<Vec2>(source.size)
        for (point in source) {
            if (result.isEmpty() || distance(result.last(), point) > DUPLICATE_DISTANCE_METERS) result += point
        }
        if (result.size > 1 && distance(result.first(), result.last()) <= DUPLICATE_DISTANCE_METERS) {
            result.removeAt(result.lastIndex)
        }
        return result
    }

    private enum class Relation { OUTSIDE, INSIDE, CROSSES_BOUNDARY }

    private const val TARGET_BOUNDARY_EDGE_METERS = 0.015f
    private const val MAX_REFINEMENT_DEPTH = 11
    private const val MIN_HOLE_AREA_SQ_METERS = 0.04f
    private const val DUPLICATE_DISTANCE_METERS = 0.0005f
    private const val EPSILON = 0.00001f
}
