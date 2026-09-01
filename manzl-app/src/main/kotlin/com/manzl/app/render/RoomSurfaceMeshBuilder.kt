package com.manzl.app.render

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Staircase
import com.manzl.app.model.Vec2
import com.manzl.app.model.VerticalVoidRoomPolicy
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry-faithful floor/ceiling mesher used after ReconstructionReadinessGate.
 *
 * Unlike the legacy surface path, this builder has no rectangular fallback. It renders only trusted
 * measured room polygons, subtracts strictly nested vertical shafts, and cuts a bounded stairwell hole
 * in the ceiling instead of deleting the ceiling of the whole room. On upper floors, measured
 * open-to-sky courtyard/lightwell faces are omitted from the floor so the void continues vertically.
 */
internal object RoomSurfaceMeshBuilder {

    data class SurfaceMesh(
        val floorVertices: FloatArray,
        val floorIndices: IntArray,
        val ceilingVertices: FloatArray,
        val ceilingIndices: IntArray,
    )

    fun build(
        plan: FloorPlan,
        ceilingHeightMeters: Float,
        levelIndex: Int,
    ): SurfaceMesh {
        val trustedRooms = plan.rooms.filter { room ->
            room.confidence >= MIN_ROOM_CONFIDENCE &&
                room.polygon.size >= 3 &&
                PolygonTriangulator.polygonArea(room.polygon) >= MIN_ROOM_AREA_SQ_METERS
        }
        val verticalVoids = trustedRooms.filter(VerticalVoidRoomPolicy::isVerticalVoid)
        val surfaceRooms = trustedRooms.filterNot(VerticalVoidRoomPolicy::isVerticalVoid)
        val acceptedStairs = plan.stairs.filter { stair -> stair.confidence >= MIN_STAIR_CONFIDENCE }

        val floor = Builder()
        val ceiling = Builder()

        for (room in surfaceRooms) {
            val nestedVoids = verticalVoids
                .filter { void -> strictlyNested(void.polygon, room.polygon) }
                .map { it.polygon }

            val openToSky = OpenAirRoomPolicy.shouldRemainOpenToSky(room)
            val floorIsVerticalVoid = levelIndex > 0 && openToSky
            if (!floorIsVerticalVoid) {
                addPolygonSurface(
                    builder = floor,
                    outer = room.polygon,
                    holes = nestedVoids,
                    y = 0f,
                    normalY = 1f,
                )
            }

            if (!openToSky) {
                val stairHoles = acceptedStairs
                    .filter { stair -> pointInsidePolygon(stair.center, room.polygon) }
                    .map(::stairwellPolygon)
                    .filter { hole -> strictlyNested(hole, room.polygon) }
                addPolygonSurface(
                    builder = ceiling,
                    outer = room.polygon,
                    holes = nestedVoids + stairHoles,
                    y = ceilingHeightMeters,
                    normalY = -1f,
                )
            }
        }

        // Stair solids live in the same floor-material stream as before, but the surrounding ceiling
        // now keeps only the measured stairwell opening instead of disappearing room-wide.
        acceptedStairs.forEach { staircase -> addStaircase(floor, staircase) }

        return SurfaceMesh(
            floorVertices = floor.vertices.toFloatArray(),
            floorIndices = floor.indices.toIntArray(),
            ceilingVertices = ceiling.vertices.toFloatArray(),
            ceilingIndices = ceiling.indices.toIntArray(),
        )
    }

    private fun addPolygonSurface(
        builder: Builder,
        outer: List<Vec2>,
        holes: List<List<Vec2>>,
        y: Float,
        normalY: Float,
    ) {
        val triangles = if (holes.isEmpty()) {
            PolygonTriangulator.triangulate(outer)
        } else {
            PolygonHoleTriangulator.triangulate(outer, holes)
        }
        if (triangles.isEmpty()) return

        val upward = normalY > 0f
        for (triangle in triangles) {
            if (upward) {
                builder.addTriangle(
                    P3(triangle.a.x, y, triangle.a.z),
                    P3(triangle.c.x, y, triangle.c.z),
                    P3(triangle.b.x, y, triangle.b.z),
                    P3(0f, normalY, 0f),
                )
            } else {
                builder.addTriangle(
                    P3(triangle.a.x, y, triangle.a.z),
                    P3(triangle.b.x, y, triangle.b.z),
                    P3(triangle.c.x, y, triangle.c.z),
                    P3(0f, normalY, 0f),
                )
            }
        }
    }

    private fun stairwellPolygon(staircase: Staircase): List<Vec2> {
        val radians = staircase.rotationDegrees * PI.toFloat() / 180f
        val runX = cos(radians)
        val runZ = sin(radians)
        val widthX = -runZ
        val widthZ = runX
        val halfRun = staircase.runMeters.coerceAtLeast(MIN_STAIR_RUN_METERS) * 0.5f + STAIRWELL_RUN_CLEARANCE_METERS
        val halfWidth = staircase.widthMeters.coerceAtLeast(MIN_STAIR_WIDTH_METERS) * 0.5f + STAIRWELL_WIDTH_CLEARANCE_METERS

        fun corner(run: Float, width: Float) = Vec2(
            x = staircase.center.x + runX * run + widthX * width,
            z = staircase.center.z + runZ * run + widthZ * width,
        )
        return listOf(
            corner(-halfRun, -halfWidth),
            corner(halfRun, -halfWidth),
            corner(halfRun, halfWidth),
            corner(-halfRun, halfWidth),
        )
    }

    private fun addStaircase(builder: Builder, staircase: Staircase) {
        if (staircase.widthMeters <= 0f || staircase.runMeters <= 0f || staircase.floorToFloorHeightMeters <= 0f) return
        val steps = staircase.stepCount.coerceIn(MIN_STAIR_STEPS, MAX_STAIR_STEPS)
        val radians = staircase.rotationDegrees * PI.toFloat() / 180f
        val runX = cos(radians)
        val runZ = sin(radians)
        val widthX = -runZ
        val widthZ = runX
        val treadDepth = staircase.runMeters / steps.toFloat()
        val riserHeight = staircase.floorToFloorHeightMeters / steps.toFloat()

        for (index in 0 until steps) {
            val runOffset = -staircase.runMeters * 0.5f + treadDepth * (index + 0.5f)
            val topHeight = riserHeight * (index + 1)
            addOrientedBox(
                builder = builder,
                centerX = staircase.center.x + runX * runOffset,
                centerY = topHeight * 0.5f,
                centerZ = staircase.center.z + runZ * runOffset,
                halfAlong = staircase.widthMeters * 0.5f,
                halfDepth = treadDepth * 0.5f,
                halfHeight = topHeight * 0.5f,
                alongX = widthX,
                alongZ = widthZ,
            )
        }
    }

    private fun addOrientedBox(
        builder: Builder,
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        halfAlong: Float,
        halfDepth: Float,
        halfHeight: Float,
        alongX: Float,
        alongZ: Float,
    ) {
        if (halfAlong <= 0f || halfDepth <= 0f || halfHeight <= 0f) return
        val normalX = -alongZ
        val normalZ = alongX

        fun corner(along: Float, depth: Float, y: Float) = P3(
            x = centerX + alongX * along + normalX * depth,
            y = centerY + y,
            z = centerZ + alongZ * along + normalZ * depth,
        )

        val a0 = corner(-halfAlong, halfDepth, -halfHeight)
        val b0 = corner(halfAlong, halfDepth, -halfHeight)
        val c0 = corner(halfAlong, -halfDepth, -halfHeight)
        val d0 = corner(-halfAlong, -halfDepth, -halfHeight)
        val a1 = corner(-halfAlong, halfDepth, halfHeight)
        val b1 = corner(halfAlong, halfDepth, halfHeight)
        val c1 = corner(halfAlong, -halfDepth, halfHeight)
        val d1 = corner(-halfAlong, -halfDepth, halfHeight)

        builder.addQuad(a0, b0, b1, a1, P3(normalX, 0f, normalZ))
        builder.addQuad(c0, d0, d1, c1, P3(-normalX, 0f, -normalZ))
        builder.addQuad(d0, a0, a1, d1, P3(-alongX, 0f, -alongZ))
        builder.addQuad(b0, c0, c1, b1, P3(alongX, 0f, alongZ))
        builder.addQuad(a1, b1, c1, d1, P3(0f, 1f, 0f))
        builder.addQuad(d0, c0, b0, a0, P3(0f, -1f, 0f))
    }

    private fun strictlyNested(inner: List<Vec2>, outer: List<Vec2>): Boolean {
        if (inner.size < 3 || outer.size < 3) return false
        if (inner.any { !pointInsidePolygon(it, outer) }) return false
        for (i in inner.indices) {
            val a = inner[i]
            val b = inner[(i + 1) % inner.size]
            for (j in outer.indices) {
                val c = outer[j]
                val d = outer[(j + 1) % outer.size]
                if (segmentsIntersect(a, b, c, d)) return false
            }
        }
        return true
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

    private fun segmentsIntersect(a: Vec2, b: Vec2, c: Vec2, d: Vec2): Boolean {
        val o1 = cross(a, b, c)
        val o2 = cross(a, b, d)
        val o3 = cross(c, d, a)
        val o4 = cross(c, d, b)
        if (((o1 > EPSILON && o2 < -EPSILON) || (o1 < -EPSILON && o2 > EPSILON)) &&
            ((o3 > EPSILON && o4 < -EPSILON) || (o3 < -EPSILON && o4 > EPSILON))
        ) return true
        if (abs(o1) <= EPSILON && onSegment(c, a, b)) return true
        if (abs(o2) <= EPSILON && onSegment(d, a, b)) return true
        if (abs(o3) <= EPSILON && onSegment(a, c, d)) return true
        if (abs(o4) <= EPSILON && onSegment(b, c, d)) return true
        return false
    }

    private fun onSegment(point: Vec2, a: Vec2, b: Vec2): Boolean =
        point.x >= minOf(a.x, b.x) - EPSILON && point.x <= maxOf(a.x, b.x) + EPSILON &&
            point.z >= minOf(a.z, b.z) - EPSILON && point.z <= maxOf(a.z, b.z) + EPSILON

    private fun cross(a: Vec2, b: Vec2, c: Vec2): Float =
        (b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x)

    private class Builder {
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Int>()

        fun addTriangle(a: P3, b: P3, c: P3, normal: P3) {
            val base = vertices.size / FLOATS_PER_VERTEX
            addVertex(a, normal)
            addVertex(b, normal)
            addVertex(c, normal)
            indices += base
            indices += base + 1
            indices += base + 2
        }

        fun addQuad(a: P3, b: P3, c: P3, d: P3, normal: P3) {
            val base = vertices.size / FLOATS_PER_VERTEX
            addVertex(a, normal)
            addVertex(b, normal)
            addVertex(c, normal)
            addVertex(d, normal)
            indices += base
            indices += base + 1
            indices += base + 2
            indices += base
            indices += base + 2
            indices += base + 3
        }

        private fun addVertex(position: P3, normal: P3) {
            vertices += position.x
            vertices += position.y
            vertices += position.z
            vertices += normal.x
            vertices += normal.y
            vertices += normal.z
        }
    }

    private data class P3(val x: Float, val y: Float, val z: Float)

    private const val MIN_ROOM_CONFIDENCE = 0.66f
    private const val MIN_ROOM_AREA_SQ_METERS = 1.2f
    private const val MIN_STAIR_CONFIDENCE = 0.66f
    private const val MIN_STAIR_WIDTH_METERS = 0.65f
    private const val MIN_STAIR_RUN_METERS = 1.2f
    private const val STAIRWELL_WIDTH_CLEARANCE_METERS = 0.06f
    private const val STAIRWELL_RUN_CLEARANCE_METERS = 0.08f
    private const val MIN_STAIR_STEPS = 4
    private const val MAX_STAIR_STEPS = 32
    private const val FLOATS_PER_VERTEX = 6
    private const val EPSILON = 0.00001f
}
