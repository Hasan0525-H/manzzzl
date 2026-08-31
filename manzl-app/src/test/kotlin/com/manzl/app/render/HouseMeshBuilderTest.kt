package com.manzl.app.render

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseMeshBuilderTest {

    @Test
    fun `single wall extrudes into five visible quads`() {
        val plan = FloorPlan(
            widthMeters = 6f,
            depthMeters = 4f,
            walls = listOf(
                WallSegment(
                    start = Vec2(-2f, 0f),
                    end = Vec2(2f, 0f),
                )
            ),
            analysisConfidence = 1f,
            sourceWidthPx = 1000,
            sourceHeightPx = 800,
        )

        val mesh = HouseMeshBuilder.build(plan)

        assertEquals(5 * 4 * 6, mesh.wallVertices.size)
        assertEquals(5 * 6, mesh.wallIndices.size)
        assertEquals(4 * 6, mesh.floorVertices.size)
        assertEquals(6, mesh.floorIndices.size)
        assertTrue(mesh.wallVertices.all { it.isFinite() })
        assertTrue(mesh.trimVertices.isEmpty())
    }

    @Test
    fun `door opening creates two jambs and a lintel`() {
        val plan = FloorPlan(
            widthMeters = 6f,
            depthMeters = 4f,
            walls = emptyList(),
            doors = listOf(
                DoorOpening(
                    center = Vec2(0f, 0f),
                    widthMeters = 1f,
                    rotationDegrees = 0f,
                    confidence = 0.9f,
                )
            ),
            analysisConfidence = 1f,
            sourceWidthPx = 1000,
            sourceHeightPx = 800,
        )

        val mesh = HouseMeshBuilder.build(plan)

        // Three boxes, six quads per box, four vertices per quad, six floats per vertex.
        assertEquals(3 * 6 * 4 * 6, mesh.trimVertices.size)
        assertEquals(3 * 6 * 6, mesh.trimIndices.size)
        assertTrue(mesh.trimVertices.all { it.isFinite() })
    }
}
