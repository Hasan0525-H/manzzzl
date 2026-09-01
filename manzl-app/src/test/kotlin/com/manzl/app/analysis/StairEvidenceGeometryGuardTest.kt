package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StairEvidenceGeometryGuardTest {

    @Test
    fun `stair fully supported by a measured hall is accepted`() {
        val plan = planWithRoom()
        val stair = evidence(center = Vec2(0f, 0f), rotation = 90f)

        assertTrue(StairEvidenceGeometryGuard.isPlausible(plan, stair))
    }

    @Test
    fun `drafting hatch crossing a strong internal divider is rejected`() {
        val divider = WallSegment(
            start = Vec2(-2.5f, 0f),
            end = Vec2(2.5f, 0f),
            thicknessMeters = 0.18f,
            confidence = 0.95f,
        )
        val plan = planWithRoom().copy(walls = listOf(divider))
        val candidate = evidence(center = Vec2(0f, 0f), rotation = 90f)

        assertFalse(StairEvidenceGeometryGuard.isPlausible(plan, candidate))
    }

    @Test
    fun `candidate whose footprint escapes its measured room is rejected`() {
        val plan = planWithRoom()
        val candidate = evidence(center = Vec2(2.75f, 0f), rotation = 90f)

        assertFalse(StairEvidenceGeometryGuard.isPlausible(plan, candidate))
    }

    private fun planWithRoom(): FloorPlan {
        val room = RoomRegion(
            id = "stair-hall",
            polygon = listOf(
                Vec2(-3f, -3f),
                Vec2(3f, -3f),
                Vec2(3f, 3f),
                Vec2(-3f, 3f),
            ),
            confidence = 0.94f,
        )
        return FloorPlan(
            widthMeters = 8f,
            depthMeters = 8f,
            walls = emptyList(),
            rooms = listOf(room),
            analysisConfidence = 1f,
            sourceWidthPx = 1200,
            sourceHeightPx = 1200,
        )
    }

    private fun evidence(center: Vec2, rotation: Float) = SemanticEvidence(
        kind = SemanticKind.STAIR,
        center = center,
        widthMeters = 1.15f,
        lengthMeters = 3.4f,
        rotationDegrees = rotation,
        confidence = 0.90f,
        source = EvidenceSource.CLASSICAL_CV,
    )
}
