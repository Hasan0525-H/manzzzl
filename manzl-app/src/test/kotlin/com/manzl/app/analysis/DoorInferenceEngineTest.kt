package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorInferenceEngineTest {

    @Test
    fun `horizontal wall gap becomes a door opening`() {
        val plan = plan(
            walls = listOf(
                WallSegment(start = Vec2(-3f, 0f), end = Vec2(-0.5f, 0f)),
                WallSegment(start = Vec2(0.5f, 0f), end = Vec2(3f, 0f)),
            )
        )

        val doors = DoorInferenceEngine.infer(plan)

        assertEquals(1, doors.size)
        assertTrue(doors.first().widthMeters in 0.9f..1.1f)
        assertTrue(kotlin.math.abs(doors.first().center.x) < 0.05f)
    }

    @Test
    fun `large structural gap is not mislabeled as a door`() {
        val plan = plan(
            walls = listOf(
                WallSegment(start = Vec2(-3f, 0f), end = Vec2(-1.4f, 0f)),
                WallSegment(start = Vec2(1.4f, 0f), end = Vec2(3f, 0f)),
            )
        )

        val doors = DoorInferenceEngine.infer(plan)

        assertTrue(doors.isEmpty())
    }

    private fun plan(walls: List<WallSegment>) = FloorPlan(
        widthMeters = 8f,
        depthMeters = 8f,
        walls = walls,
        analysisConfidence = 0.95f,
        sourceWidthPx = 1000,
        sourceHeightPx = 1000,
    )
}
