package com.manzl.app.analysis

import com.manzl.app.model.DoorHingeSide
import com.manzl.app.model.DoorOpening
import com.manzl.app.model.DoorSwingSide
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WindowOpening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningSemanticReconcilerTest {

    @Test
    fun `strong window symbol replaces geometry only door candidate`() {
        val plan = basePlan(
            door = door(confidence = 0.90f),
            window = window(confidence = 0.92f),
        )

        val result = OpeningSemanticReconciler.reconcile(plan)

        assertTrue(result.doors.isEmpty())
        assertEquals(1, result.windows.size)
    }

    @Test
    fun `trusted door swing arc beats conflicting window strokes`() {
        val plan = basePlan(
            door = door(confidence = 0.86f).copy(
                hingeSide = DoorHingeSide.AXIS_START,
                swingSide = DoorSwingSide.POSITIVE_NORMAL,
                swingConfidence = 0.88f,
            ),
            window = window(confidence = 0.93f),
        )

        val result = OpeningSemanticReconciler.reconcile(plan)

        assertEquals(1, result.doors.size)
        assertTrue(result.windows.isEmpty())
    }

    @Test
    fun `weak window evidence cannot erase a stronger doorway`() {
        val plan = basePlan(
            door = door(confidence = 0.91f),
            window = window(confidence = 0.72f),
        )

        val result = OpeningSemanticReconciler.reconcile(plan)

        assertEquals(1, result.doors.size)
        assertTrue(result.windows.isEmpty())
    }

    @Test
    fun `non overlapping door and window are both preserved`() {
        val plan = FloorPlan(
            widthMeters = 10f,
            depthMeters = 8f,
            walls = emptyList(),
            doors = listOf(door(confidence = 0.9f)),
            windows = listOf(window(confidence = 0.9f).copy(center = Vec2(3f, 0f))),
            analysisConfidence = 0.9f,
            sourceWidthPx = 1000,
            sourceHeightPx = 800,
        )

        val result = OpeningSemanticReconciler.reconcile(plan)

        assertEquals(1, result.doors.size)
        assertEquals(1, result.windows.size)
    }

    @Test
    fun `nearby perpendicular wall openings do not conflict`() {
        val plan = basePlan(
            door = door(confidence = 0.90f).copy(
                center = Vec2(0f, 0f),
                rotationDegrees = 0f,
            ),
            window = window(confidence = 0.94f).copy(
                center = Vec2(0.08f, 0.06f),
                rotationDegrees = 90f,
            ),
        )

        val result = OpeningSemanticReconciler.reconcile(plan)

        assertEquals(1, result.doors.size)
        assertEquals(1, result.windows.size)
    }

    @Test
    fun `same diagonal opening still reconciles as one semantic conflict`() {
        val plan = basePlan(
            door = door(confidence = 0.88f).copy(
                center = Vec2(1f, -0.5f),
                widthMeters = 1.15f,
                rotationDegrees = 43f,
            ),
            window = window(confidence = 0.93f).copy(
                center = Vec2(1.04f, -0.47f),
                widthMeters = 1.17f,
                rotationDegrees = 46f,
            ),
        )

        val result = OpeningSemanticReconciler.reconcile(plan)

        assertTrue(result.doors.isEmpty())
        assertEquals(1, result.windows.size)
        assertEquals(46f, result.windows.single().rotationDegrees, 0.001f)
    }

    @Test
    fun `different measured widths are not collapsed into one opening`() {
        val plan = basePlan(
            door = door(confidence = 0.88f).copy(widthMeters = 0.80f),
            window = window(confidence = 0.95f).copy(widthMeters = 1.80f),
        )

        val result = OpeningSemanticReconciler.reconcile(plan)

        assertEquals(1, result.doors.size)
        assertEquals(1, result.windows.size)
    }

    private fun basePlan(door: DoorOpening, window: WindowOpening) = FloorPlan(
        widthMeters = 10f,
        depthMeters = 8f,
        walls = emptyList(),
        doors = listOf(door),
        windows = listOf(window),
        analysisConfidence = 0.9f,
        sourceWidthPx = 1000,
        sourceHeightPx = 800,
    )

    private fun door(confidence: Float) = DoorOpening(
        center = Vec2(0f, 0f),
        widthMeters = 1.0f,
        rotationDegrees = 0f,
        confidence = confidence,
    )

    private fun window(confidence: Float) = WindowOpening(
        center = Vec2(0f, 0f),
        widthMeters = 1.0f,
        rotationDegrees = 0f,
        confidence = confidence,
    )
}
