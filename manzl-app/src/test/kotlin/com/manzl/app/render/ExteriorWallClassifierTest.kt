package com.manzl.app.render

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExteriorWallClassifierTest {

    @Test
    fun `outer wall exposes only the side away from indoor room`() {
        val wall = WallSegment(Vec2(-2f, -2f), Vec2(2f, -2f))
        val room = RoomRegion(
            id = "living",
            polygon = listOf(
                Vec2(-2f, -2f),
                Vec2(2f, -2f),
                Vec2(2f, 2f),
                Vec2(-2f, 2f),
            ),
            label = "صالة",
            confidence = 0.95f,
        )
        val exposure = ExteriorWallClassifier.classify(plan(listOf(wall), listOf(room))).single()

        // Wall axis points left -> right, therefore +normal points toward +Z (inside the room).
        assertFalse(exposure.positiveNormalExterior)
        assertTrue(exposure.negativeNormalExterior)
        assertEquals(ExteriorEvidence.ROOM_SIDE, exposure.evidence)
    }

    @Test
    fun `partition with indoor rooms on both sides is not exterior`() {
        val wall = WallSegment(Vec2(0f, -2f), Vec2(0f, 2f))
        val left = RoomRegion(
            id = "left",
            polygon = listOf(Vec2(-2f, -2f), Vec2(0f, -2f), Vec2(0f, 2f), Vec2(-2f, 2f)),
            label = "صالة",
            confidence = 0.95f,
        )
        val right = RoomRegion(
            id = "right",
            polygon = listOf(Vec2(0f, -2f), Vec2(2f, -2f), Vec2(2f, 2f), Vec2(0f, 2f)),
            label = "مجلس",
            confidence = 0.95f,
        )

        assertTrue(ExteriorWallClassifier.classify(plan(listOf(wall), listOf(left, right))).isEmpty())
    }

    @Test
    fun `courtyard side is treated as exterior facing surface`() {
        val wall = WallSegment(Vec2(-2f, 0f), Vec2(2f, 0f))
        val indoor = RoomRegion(
            id = "inside",
            polygon = listOf(Vec2(-2f, 0f), Vec2(2f, 0f), Vec2(2f, 2f), Vec2(-2f, 2f)),
            label = "صالة",
            confidence = 0.95f,
        )
        val courtyard = RoomRegion(
            id = "court",
            polygon = listOf(Vec2(-2f, -2f), Vec2(2f, -2f), Vec2(2f, 0f), Vec2(-2f, 0f)),
            label = "فناء",
            confidence = 0.95f,
        )

        val exposure = ExteriorWallClassifier.classify(plan(listOf(wall), listOf(indoor, courtyard))).single()
        assertFalse(exposure.positiveNormalExterior)
        assertTrue(exposure.negativeNormalExterior)
        assertEquals(ExteriorEvidence.ROOM_SIDE, exposure.evidence)
    }

    @Test
    fun `roomless boundary wall gets low confidence envelope fallback`() {
        val wall = WallSegment(Vec2(-3f, -3f), Vec2(3f, -3f))
        val exposure = ExteriorWallClassifier.classify(plan(listOf(wall), emptyList())).single()

        assertFalse(exposure.positiveNormalExterior)
        assertTrue(exposure.negativeNormalExterior)
        assertEquals(ExteriorEvidence.PLAN_ENVELOPE, exposure.evidence)
        assertTrue(exposure.confidence < 0.7f)
    }

    @Test
    fun `roomless central partition is not guessed exterior`() {
        val wall = WallSegment(Vec2(-2f, 0f), Vec2(2f, 0f))
        assertTrue(ExteriorWallClassifier.classify(plan(listOf(wall), emptyList())).isEmpty())
    }

    private fun plan(walls: List<WallSegment>, rooms: List<RoomRegion>) = FloorPlan(
        widthMeters = 6f,
        depthMeters = 6f,
        walls = walls,
        rooms = rooms,
        analysisConfidence = 0.9f,
        sourceWidthPx = 1000,
        sourceHeightPx = 1000,
    )
}
