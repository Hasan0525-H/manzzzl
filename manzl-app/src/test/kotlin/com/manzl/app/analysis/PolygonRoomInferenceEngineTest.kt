package com.manzl.app.analysis

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolygonRoomInferenceEngineTest {

    @Test
    fun `trapezoid room preserves diagonal boundary`() {
        val plan = plan(
            walls = listOf(
                wall(0f, 0f, 5f, 0f),
                wall(5f, 0f, 4f, 4f),
                wall(4f, 4f, 0f, 4f),
                wall(0f, 4f, 0f, 0f),
            )
        )

        val rooms = PolygonRoomInferenceEngine.infer(plan)

        assertEquals(1, rooms.size)
        assertTrue(rooms.single().polygon.any { point -> point.x in 3.9f..4.1f && point.z in 3.9f..4.1f })
        assertTrue(rooms.single().confidence >= 0.61f)
    }

    @Test
    fun `diagonal divider creates two bounded polygon faces instead of one outer room`() {
        val plan = plan(
            walls = listOf(
                wall(0f, 0f, 6f, 0f),
                wall(6f, 0f, 6f, 4f),
                wall(6f, 4f, 0f, 4f),
                wall(0f, 4f, 0f, 0f),
                wall(0f, 0f, 6f, 4f),
            )
        )

        val rooms = PolygonRoomInferenceEngine.infer(plan)

        assertEquals(2, rooms.size)
        assertTrue(rooms.all { it.polygon.size == 3 })
    }

    @Test
    fun `trusted door span closes measured wall gap for room topology`() {
        val plan = plan(
            walls = listOf(
                wall(0f, 0f, 2f, 0f),
                wall(3f, 0f, 5f, 0f),
                wall(5f, 0f, 5f, 4f),
                wall(5f, 4f, 0f, 4f),
                wall(0f, 4f, 0f, 0f),
            ),
            doors = listOf(
                DoorOpening(
                    center = Vec2(2.5f, 0f),
                    widthMeters = 1f,
                    rotationDegrees = 0f,
                    confidence = 0.92f,
                )
            ),
        )

        val rooms = PolygonRoomInferenceEngine.infer(plan)

        assertEquals(1, rooms.size)
        assertTrue(rooms.single().confidence >= 0.61f)
    }

    @Test
    fun `weak door does not fabricate closure`() {
        val plan = plan(
            walls = listOf(
                wall(0f, 0f, 2f, 0f),
                wall(3f, 0f, 5f, 0f),
                wall(5f, 0f, 5f, 4f),
                wall(5f, 4f, 0f, 4f),
                wall(0f, 4f, 0f, 0f),
            ),
            doors = listOf(
                DoorOpening(
                    center = Vec2(2.5f, 0f),
                    widthMeters = 1f,
                    rotationDegrees = 0f,
                    confidence = 0.42f,
                )
            ),
        )

        assertTrue(PolygonRoomInferenceEngine.infer(plan).isEmpty())
    }

    private fun wall(x0: Float, z0: Float, x1: Float, z1: Float) = WallSegment(
        start = Vec2(x0, z0),
        end = Vec2(x1, z1),
        thicknessMeters = 0.18f,
        confidence = 0.93f,
    )

    private fun plan(
        walls: List<WallSegment>,
        doors: List<DoorOpening> = emptyList(),
    ) = FloorPlan(
        widthMeters = 8f,
        depthMeters = 6f,
        walls = walls,
        doors = doors,
        analysisConfidence = 0.9f,
        sourceWidthPx = 1600,
        sourceHeightPx = 1200,
    )
}
