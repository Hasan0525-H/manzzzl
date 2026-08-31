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
        assertTrue(doors.first().confidence >= 0.80f)
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

    @Test
    fun `low confidence wall fragments cannot create an authoritative door`() {
        val plan = plan(
            walls = listOf(
                WallSegment(
                    start = Vec2(-3f, 0f),
                    end = Vec2(-0.5f, 0f),
                    confidence = 0.51f,
                ),
                WallSegment(
                    start = Vec2(0.5f, 0f),
                    end = Vec2(3f, 0f),
                    confidence = 0.54f,
                ),
            )
        )

        assertTrue(DoorInferenceEngine.infer(plan).isEmpty())
    }

    @Test
    fun `strongly inconsistent wall thicknesses do not form one doorway context`() {
        val plan = plan(
            walls = listOf(
                WallSegment(
                    start = Vec2(-3f, 0f),
                    end = Vec2(-0.5f, 0f),
                    thicknessMeters = 0.10f,
                    confidence = 0.92f,
                ),
                WallSegment(
                    start = Vec2(0.5f, 0f),
                    end = Vec2(3f, 0f),
                    thicknessMeters = 0.34f,
                    confidence = 0.92f,
                ),
            )
        )

        assertTrue(DoorInferenceEngine.infer(plan).isEmpty())
    }

    @Test
    fun `small measured face variation keeps a valid doorway`() {
        val plan = plan(
            walls = listOf(
                WallSegment(
                    start = Vec2(-3f, 0.03f),
                    end = Vec2(-0.48f, 0.03f),
                    thicknessMeters = 0.18f,
                    confidence = 0.78f,
                ),
                WallSegment(
                    start = Vec2(0.52f, -0.02f),
                    end = Vec2(3f, -0.02f),
                    thicknessMeters = 0.20f,
                    confidence = 0.80f,
                ),
            )
        )

        val doors = DoorInferenceEngine.infer(plan)
        assertEquals(1, doors.size)
        assertTrue(doors.first().confidence >= 0.70f)
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
