package com.manzl.app.render

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Staircase
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseMeshBuilderTest {

    @Test
    fun `single wall extrudes into closed six face prism`() {
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

        assertEquals(6 * 4 * 6, mesh.wallVertices.size)
        assertEquals(6 * 6, mesh.wallIndices.size)
        assertEquals(4 * 6, mesh.floorVertices.size)
        assertEquals(6, mesh.floorIndices.size)
        assertTrue(mesh.wallVertices.all { it.isFinite() })
        assertTrue(mesh.trimVertices.isEmpty())
        assertTrue(mesh.ceilingVertices.isEmpty())
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

        assertEquals(3 * 6 * 4 * 6, mesh.trimVertices.size)
        assertEquals(3 * 6 * 6, mesh.trimIndices.size)
        assertTrue(mesh.trimVertices.all { it.isFinite() })
    }

    @Test
    fun `validated rectangular room creates one ceiling quad`() {
        val room = RoomRegion(
            id = "room-1",
            polygon = listOf(
                Vec2(-2f, -1.5f),
                Vec2(2f, -1.5f),
                Vec2(2f, 1.5f),
                Vec2(-2f, 1.5f),
            ),
            confidence = 0.92f,
        )
        val plan = FloorPlan(
            widthMeters = 6f,
            depthMeters = 4f,
            walls = emptyList(),
            rooms = listOf(room),
            analysisConfidence = 1f,
            sourceWidthPx = 1000,
            sourceHeightPx = 800,
        )

        val mesh = HouseMeshBuilder.build(plan, wallHeightOverride = 3.2f)

        assertEquals(4 * 6, mesh.ceilingVertices.size)
        assertEquals(6, mesh.ceilingIndices.size)
        assertTrue(mesh.ceilingVertices.all { it.isFinite() })
    }

    @Test
    fun `concave L shaped room uses triangulated ceiling instead of bounding rectangle`() {
        val room = RoomRegion(
            id = "l-room",
            polygon = listOf(
                Vec2(-2f, -2f),
                Vec2(2f, -2f),
                Vec2(2f, 0f),
                Vec2(0f, 0f),
                Vec2(0f, 2f),
                Vec2(-2f, 2f),
            ),
            confidence = 0.94f,
        )
        val plan = FloorPlan(
            widthMeters = 6f,
            depthMeters = 6f,
            walls = emptyList(),
            rooms = listOf(room),
            analysisConfidence = 1f,
            sourceWidthPx = 1000,
            sourceHeightPx = 1000,
        )

        val mesh = HouseMeshBuilder.build(plan, wallHeightOverride = 3.1f)

        assertEquals(4 * 3 * 6, mesh.ceilingVertices.size)
        assertEquals(4 * 3, mesh.ceilingIndices.size)
        assertTrue(mesh.ceilingVertices.all { it.isFinite() })
    }

    @Test
    fun `room derived floors preserve a central courtyard void`() {
        val left = RoomRegion(
            id = "left-wing",
            polygon = listOf(
                Vec2(-5f, -4f),
                Vec2(-1f, -4f),
                Vec2(-1f, 4f),
                Vec2(-5f, 4f),
            ),
            confidence = 0.95f,
        )
        val right = RoomRegion(
            id = "right-wing",
            polygon = listOf(
                Vec2(1f, -4f),
                Vec2(5f, -4f),
                Vec2(5f, 4f),
                Vec2(1f, 4f),
            ),
            confidence = 0.95f,
        )
        val plan = FloorPlan(
            widthMeters = 10f,
            depthMeters = 8f,
            walls = emptyList(),
            rooms = listOf(left, right),
            analysisConfidence = 1f,
            sourceWidthPx = 1200,
            sourceHeightPx = 960,
        )

        val mesh = HouseMeshBuilder.build(plan)

        // Two separate rectangular floor quads. A global fallback slab would have only one quad.
        assertEquals(2 * 4 * 6, mesh.floorVertices.size)
        assertEquals(2 * 6, mesh.floorIndices.size)
        val xPositions = mesh.floorVertices.toList().chunked(6).map { it[0] }
        assertTrue(xPositions.none { it > -1f && it < 1f })
    }

    @Test
    fun `sparse room evidence keeps conservative whole plan fallback floor`() {
        val room = RoomRegion(
            id = "single-small-room",
            polygon = listOf(
                Vec2(-2f, -2f),
                Vec2(2f, -2f),
                Vec2(2f, 2f),
                Vec2(-2f, 2f),
            ),
            confidence = 0.96f,
        )
        val plan = FloorPlan(
            widthMeters = 10f,
            depthMeters = 10f,
            walls = emptyList(),
            rooms = listOf(room),
            analysisConfidence = 1f,
            sourceWidthPx = 1200,
            sourceHeightPx = 1200,
        )

        val mesh = HouseMeshBuilder.build(plan)

        assertEquals(4 * 6, mesh.floorVertices.size)
        assertEquals(6, mesh.floorIndices.size)
        val xPositions = mesh.floorVertices.toList().chunked(6).map { it[0] }
        assertEquals(5.25f, xPositions.maxOrNull() ?: 0f, 0.001f)
    }

    @Test
    fun `accepted staircase becomes solid steps and preserves stairwell opening`() {
        val room = RoomRegion(
            id = "stair-room",
            polygon = listOf(
                Vec2(-2f, -2f),
                Vec2(2f, -2f),
                Vec2(2f, 2f),
                Vec2(-2f, 2f),
            ),
            confidence = 0.94f,
        )
        val stair = Staircase(
            center = Vec2(0f, 0f),
            widthMeters = 1.1f,
            runMeters = 3.2f,
            rotationDegrees = 90f,
            stepCount = 8,
            floorToFloorHeightMeters = 3.2f,
            confidence = 0.90f,
        )
        val plan = FloorPlan(
            widthMeters = 7f,
            depthMeters = 6f,
            walls = emptyList(),
            stairs = listOf(stair),
            rooms = listOf(room),
            analysisConfidence = 1f,
            sourceWidthPx = 1000,
            sourceHeightPx = 800,
        )

        val mesh = HouseMeshBuilder.build(plan)

        val expectedFloorVertices = (1 * 4 * 6) + (8 * 6 * 4 * 6)
        val expectedFloorIndices = 6 + (8 * 6 * 6)
        assertEquals(expectedFloorVertices, mesh.floorVertices.size)
        assertEquals(expectedFloorIndices, mesh.floorIndices.size)
        assertTrue(mesh.floorVertices.all { it.isFinite() })
        assertTrue(mesh.ceilingVertices.isEmpty())
    }
}
