package com.manzl.app.render

import com.manzl.app.model.FloorPlan
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Builds verified compact structural columns as solid six-face prisms in the structural mesh. */
internal object StructuralColumnMeshBuilder {

    fun build(plan: FloorPlan, heightOverrideMeters: Float? = null): MeshData {
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Int>()

        plan.columns
            .asSequence()
            .filter { column ->
                column.confidence >= MIN_RENDER_CONFIDENCE &&
                    column.widthMeters >= MIN_DIMENSION_METERS &&
                    column.depthMeters >= MIN_DIMENSION_METERS
            }
            .forEach { column ->
                val height = (heightOverrideMeters ?: column.heightMeters).coerceAtLeast(MIN_HEIGHT_METERS)
                val radians = column.rotationDegrees * PI.toFloat() / 180f
                val ux = cos(radians)
                val uz = sin(radians)
                val nx = -uz
                val nz = ux
                val halfW = column.widthMeters * 0.5f
                val halfD = column.depthMeters * 0.5f

                fun corner(along: Float, depth: Float, y: Float) = P3(
                    x = column.center.x + ux * along + nx * depth,
                    y = y,
                    z = column.center.z + uz * along + nz * depth,
                )

                val a0 = corner(-halfW, halfD, 0f)
                val b0 = corner(halfW, halfD, 0f)
                val c0 = corner(halfW, -halfD, 0f)
                val d0 = corner(-halfW, -halfD, 0f)
                val a1 = corner(-halfW, halfD, height)
                val b1 = corner(halfW, halfD, height)
                val c1 = corner(halfW, -halfD, height)
                val d1 = corner(-halfW, -halfD, height)

                addQuad(vertices, indices, a0, b0, b1, a1, P3(nx, 0f, nz))
                addQuad(vertices, indices, c0, d0, d1, c1, P3(-nx, 0f, -nz))
                addQuad(vertices, indices, d0, a0, a1, d1, P3(-ux, 0f, -uz))
                addQuad(vertices, indices, b0, c0, c1, b1, P3(ux, 0f, uz))
                addQuad(vertices, indices, a1, b1, c1, d1, P3(0f, 1f, 0f))
                addQuad(vertices, indices, d0, c0, b0, a0, P3(0f, -1f, 0f))
            }

        return MeshData(
            wallVertices = vertices.toFloatArray(),
            wallIndices = indices.toIntArray(),
            floorVertices = floatArrayOf(),
            floorIndices = intArrayOf(),
            ceilingVertices = floatArrayOf(),
            ceilingIndices = intArrayOf(),
            trimVertices = floatArrayOf(),
            trimIndices = intArrayOf(),
            glassVertices = floatArrayOf(),
            glassIndices = intArrayOf(),
        )
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

    private const val MIN_RENDER_CONFIDENCE = 0.74f
    private const val MIN_DIMENSION_METERS = 0.10f
    private const val MIN_HEIGHT_METERS = 1.0f
    private const val FLOATS_PER_VERTEX = 6
}
