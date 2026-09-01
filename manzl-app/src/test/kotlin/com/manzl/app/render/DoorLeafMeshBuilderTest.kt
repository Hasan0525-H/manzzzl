package com.manzl.app.render

import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorLeafMeshBuilderTest {

    @Test
    fun `one animated leaf is a closed six face box`() {
        val pose = DoorLeafPose(
            key = DoorKey("level-0", 0),
            hinge = Vec2(-0.5f, 0f),
            tip = Vec2(0.46f, 0f),
            direction = Vec2(1f, 0f),
            leafLengthMeters = 0.96f,
            baseElevationMeters = 0f,
            heightMeters = 2.15f,
            angleDegrees = 0f,
            openFraction = 0f,
        )

        val mesh = DoorLeafMeshBuilder.build(listOf(pose))

        assertEquals(24 * 6, mesh.vertices.size)
        assertEquals(36, mesh.indices.size)
        assertTrue(mesh.vertices.all { it.isFinite() })
    }

    @Test
    fun `upper floor leaf is translated to global elevation`() {
        val pose = DoorLeafPose(
            key = DoorKey("level-1", 2),
            hinge = Vec2(1f, 2f),
            tip = Vec2(1f, 2.9f),
            direction = Vec2(0f, 1f),
            leafLengthMeters = 0.9f,
            baseElevationMeters = 3.25f,
            heightMeters = 2.10f,
            angleDegrees = 88f,
            openFraction = 1f,
        )

        val mesh = DoorLeafMeshBuilder.build(listOf(pose))
        val yValues = mesh.vertices.toList().chunked(6).map { it[1] }

        assertEquals(3.25f, yValues.minOrNull() ?: 0f, 0.001f)
        assertEquals(5.35f, yValues.maxOrNull() ?: 0f, 0.001f)
    }
}
