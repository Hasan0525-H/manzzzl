package com.manzl.app.analysis

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.FloorRegistrationDiagnostic
import com.manzl.app.model.FloorRegistrationEvidence
import com.manzl.app.model.FloorRegistrationStatus
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Staircase
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewedRegistrationApplierTest {

    @Test
    fun `reviewable stair offset moves derived upper geometry but leaves source untouched`() {
        val lower = level("level-0", 0, 0f, stairCenter = Vec2(0f, 0f))
        val upper = level("level-1", 1, 3.2f, stairCenter = Vec2(1.2f, -0.8f))
        val source = BuildingPlan(
            levels = listOf(lower, upper),
            registrationDiagnostics = listOf(
                FloorRegistrationDiagnostic(
                    lowerLevelId = lower.id,
                    upperLevelId = upper.id,
                    status = FloorRegistrationStatus.REVIEW_REQUIRED,
                    evidence = FloorRegistrationEvidence.STAIR_SHAFT,
                    suggestedOffsetXMeters = -1.2f,
                    suggestedOffsetZMeters = 0.8f,
                    confidence = 0.91f,
                )
            ),
        )

        val result = ReviewedRegistrationApplier.applyAllReviewable(source)

        assertEquals(1, result.appliedPairCount)
        assertNotSame(source, result.building)
        assertEquals(1.2f, source.levels[1].plan.stairs.single().center.x, 0.0001f)
        assertEquals(-0.8f, source.levels[1].plan.stairs.single().center.z, 0.0001f)
        assertEquals(0f, result.building.levels[1].plan.stairs.single().center.x, 0.0001f)
        assertEquals(0f, result.building.levels[1].plan.stairs.single().center.z, 0.0001f)
        assertEquals(-1.2f, result.building.levels[1].plan.walls.single().start.x, 0.0001f)
        assertEquals(0.8f, result.building.levels[1].plan.rooms.single().polygon.first().z, 0.0001f)
        assertTrue(result.building.registrationDiagnostics.all { it.status == FloorRegistrationStatus.ALIGNED })
    }

    @Test
    fun `aligned floor inherits lower approved offset across three level chain`() {
        val level0 = level("level-0", 0, 0f, stairCenter = Vec2(0f, 0f))
        val level1 = level("level-1", 1, 3.2f, stairCenter = Vec2(1f, 0f))
        val level2 = level("level-2", 2, 6.4f, stairCenter = Vec2(1f, 0f))
        val source = BuildingPlan(
            levels = listOf(level0, level1, level2),
            registrationDiagnostics = listOf(
                FloorRegistrationDiagnostic(
                    lowerLevelId = level0.id,
                    upperLevelId = level1.id,
                    status = FloorRegistrationStatus.REVIEW_REQUIRED,
                    evidence = FloorRegistrationEvidence.STAIR_SHAFT,
                    suggestedOffsetXMeters = -1f,
                    suggestedOffsetZMeters = 0f,
                    confidence = 0.90f,
                ),
                FloorRegistrationDiagnostic(
                    lowerLevelId = level1.id,
                    upperLevelId = level2.id,
                    status = FloorRegistrationStatus.ALIGNED,
                    evidence = FloorRegistrationEvidence.STAIR_SHAFT,
                    confidence = 0.90f,
                ),
            ),
        )

        val result = ReviewedRegistrationApplier.applyAllReviewable(source)

        assertEquals(1, result.appliedPairCount)
        assertEquals(0f, result.building.levels[1].plan.stairs.single().center.x, 0.0001f)
        assertEquals(0f, result.building.levels[2].plan.stairs.single().center.x, 0.0001f)
    }

    @Test
    fun `unresolved or low confidence diagnostic is never applied`() {
        val lower = level("level-0", 0, 0f, stairCenter = Vec2(0f, 0f))
        val upper = level("level-1", 1, 3.2f, stairCenter = Vec2(2f, 0f))
        val lowConfidence = BuildingPlan(
            levels = listOf(lower, upper),
            registrationDiagnostics = listOf(
                FloorRegistrationDiagnostic(
                    lowerLevelId = lower.id,
                    upperLevelId = upper.id,
                    status = FloorRegistrationStatus.REVIEW_REQUIRED,
                    evidence = FloorRegistrationEvidence.STAIR_SHAFT,
                    suggestedOffsetXMeters = -2f,
                    suggestedOffsetZMeters = 0f,
                    confidence = 0.60f,
                )
            ),
        )

        val result = ReviewedRegistrationApplier.applyAllReviewable(lowConfidence)

        assertEquals(0, result.appliedPairCount)
        assertEquals(lowConfidence, result.building)
        assertEquals(2f, result.building.levels[1].plan.stairs.single().center.x, 0.0001f)
    }

    private fun level(
        id: String,
        index: Int,
        elevation: Float,
        stairCenter: Vec2,
    ): FloorLevel {
        val stair = Staircase(
            center = stairCenter,
            widthMeters = 1.1f,
            runMeters = 3.2f,
            rotationDegrees = 0f,
            stepCount = 18,
            floorToFloorHeightMeters = 3.2f,
            confidence = 0.93f,
        )
        val plan = FloorPlan(
            widthMeters = 10f,
            depthMeters = 8f,
            walls = listOf(WallSegment(start = Vec2(0f, 0f), end = Vec2(3f, 0f))),
            stairs = listOf(stair),
            rooms = listOf(
                RoomRegion(
                    id = "room-$index",
                    polygon = listOf(Vec2(0f, 0f), Vec2(2f, 0f), Vec2(2f, 2f), Vec2(0f, 2f)),
                    confidence = 0.9f,
                )
            ),
            analysisConfidence = 0.95f,
            sourceWidthPx = 1200,
            sourceHeightPx = 900,
        )
        return FloorLevel(
            id = id,
            levelIndex = index,
            baseElevationMeters = elevation,
            plan = plan,
        )
    }
}
