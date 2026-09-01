package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryEvidenceFusionTest {

    @Test
    fun `AI door is anchored to the measured gap rather than its noisy center`() {
        val base = horizontalGapPlan()
        val evidence = SemanticEvidence(
            kind = SemanticKind.DOOR,
            center = Vec2(0.04f, -2.12f),
            widthMeters = 0.98f,
            rotationDegrees = 3f,
            confidence = 0.91f,
            source = EvidenceSource.LOCAL_AI,
        )

        val result = GeometryEvidenceFusion.fuse(base, listOf(evidence))

        assertEquals(1, result.doors.size)
        val door = result.doors.first()
        assertTrue(kotlin.math.abs(door.center.x) < 0.001f)
        assertTrue(kotlin.math.abs(door.center.z + 2f) < 0.001f)
        assertEquals(1f, door.widthMeters, 0.01f)
        assertEquals(0f, door.rotationDegrees, 0.01f)
    }

    @Test
    fun `semantic door cannot punch a new opening through a continuous wall`() {
        val base = rectangularPlan()
        val evidence = SemanticEvidence(
            kind = SemanticKind.DOOR,
            center = Vec2(0f, -2.02f),
            widthMeters = 0.95f,
            rotationDegrees = 0f,
            confidence = 0.99f,
            source = EvidenceSource.LOCAL_AI,
        )

        val result = GeometryEvidenceFusion.fuse(base, listOf(evidence))

        assertTrue(result.doors.isEmpty())
    }

    @Test
    fun `AI door far from every measured gap is rejected`() {
        val base = horizontalGapPlan()
        val evidence = SemanticEvidence(
            kind = SemanticKind.DOOR,
            center = Vec2(0f, 0f),
            widthMeters = 0.95f,
            confidence = 0.99f,
            source = EvidenceSource.LOCAL_AI,
        )

        val result = GeometryEvidenceFusion.fuse(base, listOf(evidence))

        assertTrue(result.doors.isEmpty())
    }

    @Test
    fun `plausible window is accepted from a measured vertical gap`() {
        val base = verticalGapPlan()
        val evidence = SemanticEvidence(
            kind = SemanticKind.WINDOW,
            center = Vec2(3.10f, 0.04f),
            widthMeters = 1.18f,
            rotationDegrees = 89f,
            confidence = 0.83f,
            source = EvidenceSource.LOCAL_AI,
        )

        val result = GeometryEvidenceFusion.fuse(base, listOf(evidence))

        assertEquals(1, result.windows.size)
        val window = result.windows.first()
        assertTrue(kotlin.math.abs(window.center.x - 3f) < 0.001f)
        assertTrue(kotlin.math.abs(window.center.z) < 0.001f)
        assertEquals(1.2f, window.widthMeters, 0.01f)
        assertEquals(90f, window.rotationDegrees, 0.01f)
    }

    @Test
    fun `diagonal measured gap preserves arbitrary opening angle`() {
        val base = diagonalGapPlan()
        val evidence = SemanticEvidence(
            kind = SemanticKind.DOOR,
            center = Vec2(0.06f, -0.04f),
            widthMeters = 1.40f,
            rotationDegrees = 47f,
            confidence = 0.92f,
            source = EvidenceSource.LOCAL_AI,
        )

        val result = GeometryEvidenceFusion.fuse(base, listOf(evidence))

        assertEquals(1, result.doors.size)
        val door = result.doors.first()
        assertTrue(kotlin.math.abs(door.center.x) < 0.01f)
        assertTrue(kotlin.math.abs(door.center.z) < 0.01f)
        assertEquals(45f, door.rotationDegrees, 0.2f)
        assertEquals(1.414f, door.widthMeters, 0.03f)
    }

    @Test
    fun `opening evidence with incompatible angle is rejected even near a real gap`() {
        val base = diagonalGapPlan()
        val evidence = SemanticEvidence(
            kind = SemanticKind.WINDOW,
            center = Vec2(0f, 0f),
            widthMeters = 1.41f,
            rotationDegrees = 0f,
            confidence = 0.96f,
            source = EvidenceSource.LOCAL_AI,
        )

        assertTrue(GeometryEvidenceFusion.fuse(base, listOf(evidence)).windows.isEmpty())
    }

    @Test
    fun `room polygon outside measured plan is rejected`() {
        val base = rectangularPlan()
        val evidence = SemanticEvidence(
            kind = SemanticKind.ROOM,
            center = Vec2(0f, 0f),
            polygon = listOf(
                Vec2(-1f, -1f),
                Vec2(4.5f, -1f),
                Vec2(4.5f, 1f),
                Vec2(-1f, 1f),
            ),
            label = "مجلس",
            confidence = 0.95f,
            source = EvidenceSource.LOCAL_AI,
        )

        val result = GeometryEvidenceFusion.fuse(base, listOf(evidence))

        assertTrue(result.rooms.isEmpty())
    }

    private fun rectangularPlan(): FloorPlan = FloorPlan(
        widthMeters = 6f,
        depthMeters = 4f,
        walls = listOf(
            WallSegment(Vec2(-3f, -2f), Vec2(3f, -2f)),
            WallSegment(Vec2(3f, -2f), Vec2(3f, 2f)),
            WallSegment(Vec2(3f, 2f), Vec2(-3f, 2f)),
            WallSegment(Vec2(-3f, 2f), Vec2(-3f, -2f)),
        ),
        analysisConfidence = 0.94f,
        sourceWidthPx = 1200,
        sourceHeightPx = 800,
    )

    private fun horizontalGapPlan(): FloorPlan = FloorPlan(
        widthMeters = 6f,
        depthMeters = 4f,
        walls = listOf(
            WallSegment(Vec2(-3f, -2f), Vec2(-0.5f, -2f), confidence = 0.92f),
            WallSegment(Vec2(0.5f, -2f), Vec2(3f, -2f), confidence = 0.94f),
            WallSegment(Vec2(3f, -2f), Vec2(3f, 2f)),
            WallSegment(Vec2(3f, 2f), Vec2(-3f, 2f)),
            WallSegment(Vec2(-3f, 2f), Vec2(-3f, -2f)),
        ),
        analysisConfidence = 0.94f,
        sourceWidthPx = 1200,
        sourceHeightPx = 800,
    )

    private fun verticalGapPlan(): FloorPlan = FloorPlan(
        widthMeters = 6f,
        depthMeters = 4f,
        walls = listOf(
            WallSegment(Vec2(-3f, -2f), Vec2(3f, -2f)),
            WallSegment(Vec2(3f, -2f), Vec2(3f, -0.6f), confidence = 0.90f),
            WallSegment(Vec2(3f, 0.6f), Vec2(3f, 2f), confidence = 0.93f),
            WallSegment(Vec2(3f, 2f), Vec2(-3f, 2f)),
            WallSegment(Vec2(-3f, 2f), Vec2(-3f, -2f)),
        ),
        analysisConfidence = 0.94f,
        sourceWidthPx = 1200,
        sourceHeightPx = 800,
    )

    private fun diagonalGapPlan(): FloorPlan = FloorPlan(
        widthMeters = 8f,
        depthMeters = 8f,
        walls = listOf(
            WallSegment(Vec2(-3f, -3f), Vec2(-0.5f, -0.5f), confidence = 0.93f),
            WallSegment(Vec2(0.5f, 0.5f), Vec2(3f, 3f), confidence = 0.94f),
        ),
        analysisConfidence = 0.94f,
        sourceWidthPx = 1200,
        sourceHeightPx = 1200,
    )
}
