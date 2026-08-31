package com.manzl.app.render

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.WallSegment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class MeshData(
    val wallVertices: FloatArray,
    val wallIndices: IntArray,
    val floorVertices: FloatArray,
    val floorIndices: IntArray,
    val trimVertices: FloatArray,
    val trimIndices: IntArray,
)

internal object HouseMeshBuilder {

    fun build(
        plan: FloorPlan,
        wallHeightOverride: Float? = null,
        doorHeightOverride: Float? = null,
    ): MeshData {
        val wallBuilder = GeometryBuilder()
        for (wall in plan.walls) {
            addWall(
                builder = wallBuilder,
                wall = if (wallHeightOverride == null) wall else wall.copy(heightMeters = wallHeightOverride),
            )
        }

        val floorBuilder = GeometryBuilder()
        val halfWidth = plan.widthMeters / 2f + 0.25f
        val halfDepth = plan.depthMeters / 2f + 0.25f
        floorBuilder.addQuad(
            a = P3(-halfWidth, 0f, -halfDepth),
            b = P3(halfWidth, 0f, -halfDepth),
            c = P3(halfWidth, 0f, halfDepth),
            d = P3(-halfWidth, 0f, halfDepth),
            normal = P3(0f, 1f, 0f),
        )

        val trimBuilder = GeometryBuilder()
        val doorHeight = doorHeightOverride ?: DEFAULT_DOOR_HEIGHT_METERS
        for (door in plan.doors) {
            addDoorFrame(trimBuilder, door, doorHeight)
        }

        return MeshData(
            wallVertices = wallBuilder.vertices.toFloatArray(),
            wallIndices = wallBuilder.indices.toIntArray(),
            floorVertices = floorBuilder.vertices.toFloatArray(),
            floorIndices = floorBuilder.indices.toIntArray(),
            trimVertices = trimBuilder.vertices.toFloatArray(),
            trimIndices = trimBuilder.indices.toIntArray(),
        )
    }

    private fun addWall(builder: GeometryBuilder, wall: WallSegment) {
        val start = wall.start
        val end = wall.end
        val dx = end.x - start.x
        val dz = end.z - start.z
        val length = sqrt(dx * dx + dz * dz)
        if (length < 0.001f) return

        val nx = -dz / length
        val nz = dx / length
        val halfThickness = wall.thicknessMeters / 2f
        val ox = nx * halfThickness
        val oz = nz * halfThickness
        val h = wall.heightMeters

        val a0 = P3(start.x + ox, 0f, start.z + oz)
        val b0 = P3(end.x + ox, 0f, end.z + oz)
        val c0 = P3(end.x - ox, 0f, end.z - oz)
        val d0 = P3(start.x - ox, 0f, start.z - oz)

        val a1 = a0.copy(y = h)
        val b1 = b0.copy(y = h)
        val c1 = c0.copy(y = h)
        val d1 = d0.copy(y = h)

        builder.addQuad(a0, b0, b1, a1, P3(nx, 0f, nz))
        builder.addQuad(c0, d0, d1, c1, P3(-nx, 0f, -nz))

        val alongX = dx / length
        val alongZ = dz / length
        builder.addQuad(d0, a0, a1, d1, P3(-alongX, 0f, -alongZ))
        builder.addQuad(b0, c0, c1, b1, P3(alongX, 0f, alongZ))
        builder.addQuad(a1, b1, c1, d1, P3(0f, 1f, 0f))
    }

    private fun addDoorFrame(builder: GeometryBuilder, door: DoorOpening, doorHeight: Float) {
        val radians = door.rotationDegrees * (PI.toFloat() / 180f)
        val alongX = cos(radians)
        val alongZ = sin(radians)
        val halfGap = door.widthMeters / 2f
        val postOffset = halfGap + DOOR_JAMB_WIDTH_METERS / 2f

        addOrientedBox(
            builder = builder,
            centerX = door.center.x - alongX * postOffset,
            centerY = doorHeight / 2f,
            centerZ = door.center.z - alongZ * postOffset,
            halfAlong = DOOR_JAMB_WIDTH_METERS / 2f,
            halfDepth = DOOR_FRAME_DEPTH_METERS / 2f,
            halfHeight = doorHeight / 2f,
            alongX = alongX,
            alongZ = alongZ,
        )
        addOrientedBox(
            builder = builder,
            centerX = door.center.x + alongX * postOffset,
            centerY = doorHeight / 2f,
            centerZ = door.center.z + alongZ * postOffset,
            halfAlong = DOOR_JAMB_WIDTH_METERS / 2f,
            halfDepth = DOOR_FRAME_DEPTH_METERS / 2f,
            halfHeight = doorHeight / 2f,
            alongX = alongX,
            alongZ = alongZ,
        )
        addOrientedBox(
            builder = builder,
            centerX = door.center.x,
            centerY = doorHeight + DOOR_LINTEL_HEIGHT_METERS / 2f,
            centerZ = door.center.z,
            halfAlong = halfGap + DOOR_JAMB_WIDTH_METERS,
            halfDepth = DOOR_FRAME_DEPTH_METERS / 2f,
            halfHeight = DOOR_LINTEL_HEIGHT_METERS / 2f,
            alongX = alongX,
            alongZ = alongZ,
        )
    }

    private fun addOrientedBox(
        builder: GeometryBuilder,
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        halfAlong: Float,
        halfDepth: Float,
        halfHeight: Float,
        alongX: Float,
        alongZ: Float,
    ) {
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

    private class GeometryBuilder {
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Int>()

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

    private data class P3(
        val x: Float,
        val y: Float,
        val z: Float,
    )

    private const val DEFAULT_DOOR_HEIGHT_METERS = 2.20f
    private const val DOOR_JAMB_WIDTH_METERS = 0.075f
    private const val DOOR_LINTEL_HEIGHT_METERS = 0.085f
    private const val DOOR_FRAME_DEPTH_METERS = 0.20f
    private const val FLOATS_PER_VERTEX = 6
}
