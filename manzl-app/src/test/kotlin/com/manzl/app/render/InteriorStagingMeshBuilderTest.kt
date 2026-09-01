package com.manzl.app.render

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import org.junit.Assert.assertTrue
import org.junit.Test

class InteriorStagingMeshBuilderTest {

    @Test
    fun `stages a high confidence labelled majlis without changing architecture`() {
        val plan = planWithRoom(label = "مجلس", confidence = 0.94f)

        val staging = InteriorStagingMeshBuilder.build(plan)

        assertTrue(staging.floorVertices.isNotEmpty())
        assertTrue(staging.floorIndices.isNotEmpty())
        assertTrue(staging.trimVertices.isNotEmpty())
        assertTrue(staging.trimIndices.isNotEmpty())
        assertTrue(staging.ceilingVertices.isEmpty())
        assertTrue(staging.glassVertices.isEmpty())
    }

    @Test
    fun `does not stage an unlabeled room`() {
        val staging = InteriorStagingMeshBuilder.build(planWithRoom(label = null, confidence = 0.95f))

        assertTrue(staging.wallVertices.isEmpty())
        assertTrue(staging.floorVertices.isEmpty())
        assertTrue(staging.trimVertices.isEmpty())
    }

    @Test
    fun `does not stage low confidence semantic room evidence`() {
        val staging = InteriorStagingMeshBuilder.build(planWithRoom(label = "غرفة نوم", confidence = 0.62f))

        assertTrue(staging.wallVertices.isEmpty())
        assertTrue(staging.floorVertices.isEmpty())
        assertTrue(staging.trimVertices.isEmpty())
    }

    @Test
    fun `open air labels remain free of invented furniture`() {
        val staging = InteriorStagingMeshBuilder.build(planWithRoom(label = "فناء", confidence = 0.94f))

        assertTrue(staging.wallVertices.isEmpty())
        assertTrue(staging.floorVertices.isEmpty())
        assertTrue(staging.trimVertices.isEmpty())
    }

    private fun planWithRoom(label: String?, confidence: Float): FloorPlan = FloorPlan(
        widthMeters = 6f,
        depthMeters = 5f,
        walls = emptyList(),
        rooms = listOf(
            RoomRegion(
                id = "room-1",
                polygon = listOf(
                    Vec2(-3f, -2.5f),
                    Vec2(3f, -2.5f),
                    Vec2(3f, 2.5f),
                    Vec2(-3f, 2.5f),
                ),
                label = label,
                confidence = confidence,
            )
        ),
        analysisConfidence = 0.95f,
        sourceWidthPx = 1200,
        sourceHeightPx = 1000,
    )
}
