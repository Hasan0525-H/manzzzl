package com.manzl.app.render

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
    }
}
