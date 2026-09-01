package com.manzl.app.analysis

import com.manzl.app.model.DoorEvidenceKind
import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomBoundarySupportEvaluatorTest {

    @Test
    fun `closed measured rectangle has full boundary support`() {
        val room = room(
            "main",
            listOf(Vec2(-4f, -3f), Vec2(4f, -3f), Vec2(4f, 3f), Vec2(-4f, 3f)),
        )
        val issues = RoomBoundarySupportEvaluator.findUnsupportedRooms(
            plan = plan(rectangleWalls(), listOf(room)),
            rooms = listOf(room),
        )

        assertTrue(issues.isEmpty())
    }

    @Test
    fun `invented room edge through open space is rejected`() {
        val walls = rectangleWalls().filterIndexed { index, _ -> index != 1 }
        val room = room(
            "main",
            listOf(Vec2(-4f, -3f), Vec2(4f, -3f), Vec2(4f, 3f), Vec2(-4f, 3f)),
        )
        val issues = RoomBoundarySupportEvaluator.findUnsupportedRooms(
            plan = plan(walls, listOf(room)),
            rooms = listOf(room),
        )

        assertEquals(1, issues.size)
        assertTrue(issues.single().weakestEdgeSupport < 0.20f)
    }

    @Test
    fun `classified door supports a measured boundary gap`() {
        val walls = listOf(
            WallSegment(Vec2(-4f, -3f), Vec2(-0.55f, -3f), confidence = 0.96f),
            WallSegment(Vec2(0.55f, -3f), Vec2(4f, -3f), confidence = 0.96f),
            WallSegment(Vec2(4f, -3f), Vec2(4f, 3f), confidence = 0.96f),
            WallSegment(Vec2(4f, 3f), Vec2(-4f, 3f), confidence = 0.96f),
            WallSegment(Vec2(-4f, 3f), Vec2(-4f, -3f), confidence = 0.96f),
        )
        val room = room(
            "main",
            listOf(Vec2(-4f, -3f), Vec2(4f, -3f), Vec2(4f, 3f), Vec2(-4f, 3f)),
        )
        val door = DoorOpening(
            center = Vec2(0f, -3f),
            widthMeters = 1.1f,
            rotationDegrees = 0f,
            confidence = 0.94f,
            evidenceKind = DoorEvidenceKind.SEMANTIC_CONFIRMED,
        )
        val plan = plan(walls, listOf(room)).copy(doors = listOf(door))

        assertTrue(RoomBoundarySupportEvaluator.findUnsupportedRooms(plan, listOf(room)).isEmpty())
    }

    @Test
    fun `diagonal measured room remains supported`() {
        val polygon = listOf(
            Vec2(0f, -3f),
            Vec2(3f, 0f),
            Vec2(0f, 3f),
            Vec2(-3f, 0f),
        )
        val walls = polygon.indices.map { index ->
            WallSegment(
                start = polygon[index],
                end = polygon[(index + 1) % polygon.size],
                thicknessMeters = 0.20f,
                confidence = 0.95f,
            )
        }
        val room = room("diamond", polygon)
        val plan = plan(walls, listOf(room))

        assertTrue(RoomBoundarySupportEvaluator.findUnsupportedRooms(plan, listOf(room)).isEmpty())
    }

    private fun rectangleWalls(): List<WallSegment> = listOf(
        WallSegment(Vec2(-4f, -3f), Vec2(4f, -3f), thicknessMeters = 0.18f, confidence = 0.95f),
        WallSegment(Vec2(4f, -3f), Vec2(4f, 3f), thicknessMeters = 0.18f, confidence = 0.95f),
        WallSegment(Vec2(4f, 3f), Vec2(-4f, 3f), thicknessMeters = 0.18f, confidence = 0.95f),
        WallSegment(Vec2(-4f, 3f), Vec2(-4f, -3f), thicknessMeters = 0.18f, confidence = 0.95f),
    )

    private fun room(id: String, polygon: List<Vec2>) = RoomRegion(
        id = id,
        polygon = polygon,
        confidence = 0.95f,
    )

    private fun plan(walls: List<WallSegment>, rooms: List<RoomRegion>) = FloorPlan(
        widthMeters = 10f,
        depthMeters = 8f,
        walls = walls,
        rooms = rooms,
        analysisConfidence = 0.95f,
        sourceWidthPx = 1200,
        sourceHeightPx = 960,
    )
}
