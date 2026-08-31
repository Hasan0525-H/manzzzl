package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryEvidenceFusionTest {

    @Test
    fun `AI door is snapped onto measured wall`() {
        val base = rectangularPlan()
        val evidence = SemanticEvidence(
            kind = SemanticKind.DOOR,
            center = Vec2(0.04f, -2.12f),
            widthMeters = 0.95f,
            rotationDegrees = 3f,
            confidence = 0.91f,
            source = EvidenceSource.LOCAL_AI,
        )

        val result = GeometryEvidenceFusion.fuse(base, listOf(evidence))

        assertEquals(1, result.doors.size)
        assertTrue(kotlin.math.abs(result.doors.first().center.z + 2f) < 0.001f)
        assertEquals(0f, result.doors.first().rotationDegrees)
    }

    @Test
    fun `AI door far from every wall is rejected`() {
        val base = rectangularPlan()
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
    fun `plausible window is accepted and snapped to wall`() {
        val base = rectangularPlan()
        val evidence = SemanticEvidence(
            kind = SemanticKind.WINDOW,
            center = Vec2(3.10f, 0.3f),
            widthMeters = 1.8f,
            rotationDegrees = 89f,
            confidence = 0.83f,
            source = EvidenceSource.LOCAL_AI,
        )

        val result = GeometryEvidenceFusion.fuse(base, listOf(evidence))

        assertEquals(1, result.windows.size)
        assertTrue(kotlin.math.abs(result.windows.first().center.x - 3f) < 0.001f)
        assertEquals(90f, result.windows.first().rotationDegrees)
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
}
