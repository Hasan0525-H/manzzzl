package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomInferenceEngineTest {

    @Test
    fun `enclosed room survives a door sized wall gap`() {
        val plan = plan(
            walls = listOf(
                horizontal(-2f, 2f, -1.5f),
                horizontal(-2f, 2f, 1.5f),
                vertical(-2f, -1.5f, 1.5f),
                vertical(2f, -1.5f, -0.55f),
                vertical(2f, 0.55f, 1.5f),
            )
        )

        val rooms = RoomInferenceEngine.infer(plan)

        assertEquals(1, rooms.size)
        assertEquals(4, rooms.single().polygon.size)
        assertTrue(rooms.single().confidence >= 0.70f)
    }

    @Test
    fun `strong internal divider prevents composite outer room`() {
        val plan = plan(
            walls = listOf(
                horizontal(-4f, 4f, -2f),
                horizontal(-4f, 4f, 2f),
                vertical(-4f, -2f, 2f),
                vertical(4f, -2f, 2f),
                vertical(0f, -2f, 2f),
            )
        )

        val rooms = RoomInferenceEngine.infer(plan)

        assertEquals(2, rooms.size)
        val widths = rooms.map { room ->
            room.polygon.maxOf { it.x } - room.polygon.minOf { it.x }
        }
        assertTrue(widths.all { kotlin.math.abs(it - 4f) < 0.05f })
    }

    private fun horizontal(x0: Float, x1: Float, z: Float) =
        WallSegment(start = Vec2(x0, z), end = Vec2(x1, z), thicknessMeters = 0.18f)

    private fun vertical(x: Float, z0: Float, z1: Float) =
        WallSegment(start = Vec2(x, z0), end = Vec2(x, z1), thicknessMeters = 0.18f)

    private fun plan(walls: List<WallSegment>) = FloorPlan(
        widthMeters = 10f,
        depthMeters = 8f,
        walls = walls,
        analysisConfidence = 1f,
        sourceWidthPx = 1200,
        sourceHeightPx = 900,
    )
}
