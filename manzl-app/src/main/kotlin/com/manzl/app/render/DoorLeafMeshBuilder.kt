package com.manzl.app.render

import kotlin.math.sqrt

/** Builds one batched dynamic mesh for all currently animated door leaves. */
internal object DoorLeafMeshBuilder {

    fun build(poses: List<DoorLeafPose>): DoorLeafMeshData {
        if (poses.isEmpty()) return DoorLeafMeshData(floatArrayOf(), intArrayOf())
        val vertices = ArrayList<Float>(poses.size * FLOATS_PER_BOX_VERTEX_DATA)
        val indices = ArrayList<Int>(poses.size * INDICES_PER_BOX)

        poses.forEach { pose ->
            addLeaf(vertices, indices, pose)
        }
        return DoorLeafMeshData(
            vertices = vertices.toFloatArray(),
            indices = indices.toIntArray(),
        )
    }

    private fun addLeaf(
        vertices: MutableList<Float>,
        indices: MutableList<Int>,
        pose: DoorLeafPose,
    ) {
        val length = sqrt(
            pose.direction.x * pose.direction.x + pose.direction.z * pose.direction.z
        )
        if (length <= EPSILON || pose.leafLengthMeters <= 0f || pose.heightMeters <= 0f) return

        val alongX = pose.direction.x / length
        val alongZ = pose.direction.z / length
        val normalX = -alongZ
        val normalZ = alongX
        val centerX = pose.hinge.x + alongX * pose.leafLengthMeters * 0.5f
        val centerZ = pose.hinge.z + alongZ * pose.leafLengthMeters * 0.5f
        val centerY = pose.baseElevationMeters + pose.heightMeters * 0.5f
        val halfAlong = pose.leafLengthMeters * 0.5f
        val halfDepth = DOOR_LEAF_THICKNESS_METERS * 0.5f
        val halfHeight = pose.heightMeters * 0.5f

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

        addQuad(vertices, indices, a0, b0, b1, a1, P3(normalX, 0f, normalZ))
        addQuad(vertices, indices, c0, d0, d1, c1, P3(-normalX, 0f, -normalZ))
        addQuad(vertices, indices, d0, a0, a1, d1, P3(-alongX, 0f, -alongZ))
        addQuad(vertices, indices, b0, c0, c1, b1, P3(alongX, 0f, alongZ))
        addQuad(vertices, indices, a1, b1, c1, d1, P3(0f, 1f, 0f))
        addQuad(vertices, indices, d0, c0, b0, a0, P3(0f, -1f, 0f))
    }

    private fun addQuad(
        vertices: MutableList<Float>,
        indices: MutableList<Int>,
        a: P3,
        b: P3,
        c: P3,
        d: P3,
        normal: P3,
    ) {
        val base = vertices.size / FLOATS_PER_VERTEX
        addVertex(vertices, a, normal)
        addVertex(vertices, b, normal)
        addVertex(vertices, c, normal)
        addVertex(vertices, d, normal)
        indices += base
        indices += base + 1
        indices += base + 2
        indices += base
        indices += base + 2
        indices += base + 3
    }

    private fun addVertex(vertices: MutableList<Float>, position: P3, normal: P3) {
        vertices += position.x
        vertices += position.y
        vertices += position.z
        vertices += normal.x
        vertices += normal.y
        vertices += normal.z
    }

    private data class P3(val x: Float, val y: Float, val z: Float)

    private const val FLOATS_PER_VERTEX = 6
    private const val VERTICES_PER_BOX = 24
    private const val FLOATS_PER_BOX_VERTEX_DATA = VERTICES_PER_BOX * FLOATS_PER_VERTEX
    private const val INDICES_PER_BOX = 36
    private const val DOOR_LEAF_THICKNESS_METERS = 0.042f
    private const val EPSILON = 0.000001f
}

internal data class DoorLeafMeshData(
    val vertices: FloatArray,
    val indices: IntArray,
)
