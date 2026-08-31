package com.manzl.app.analysis

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Staircase
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import com.manzl.app.model.WindowOpening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricScaleReviewApplierTest {

    @Test
    fun `fallback scale is reviewable`() {
        assertTrue(MetricScaleReviewApplier.needsReview(plan()))
    }

    @Test
    fun `explicit overall dimension uniformly rescales xz geometry only`() {
        val sourcePlan = plan()
        val source = BuildingPlan(
            levels = listOf(FloorLevel("level-0", 0, 0f, sourcePlan)),
        )

        val result = MetricScaleReviewApplier.apply(
            source = source,
            reviewedLongSideMetersByLevelId = mapOf("level-0" to 28f),
        )
        val corrected = result.building.levels.single().plan

        assertEquals(1, result.correctedLevelCount)
        assertEquals(28f, corrected.widthMeters, 0.001f)
        assertEquals(16f, corrected.depthMeters, 0.001f)
        assertEquals(-10f, corrected.walls.single().start.x, 0.001f)
        assertEquals(2f, corrected.doors.single().widthMeters, 0.001f)
        assertEquals(3f, corrected.windows.single().widthMeters, 0.001f)
        assertEquals(2.4f, corrected.stairs.single().widthMeters, 0.001f)
        assertEquals(6f, corrected.stairs.single().runMeters, 0.001f)
        assertEquals(-4f, corrected.rooms.single().polygon.first().x, 0.001f)

        // Vertical architectural values are independent from 2D drawing scale.
        assertEquals(3f, corrected.walls.single().heightMeters, 0.001f)
        assertEquals(0.9f, corrected.windows.single().sillHeightMeters, 0.001f)
        assertEquals(3.2f, corrected.stairs.single().floorToFloorHeightMeters, 0.001f)
        assertEquals(1f, corrected.scaleConfidence, 0.001f)
        assertEquals("user_overall_dimension", corrected.scaleSource)
        assertFalse(MetricScaleReviewApplier.needsReview(corrected))
    }

    @Test
    fun `invalid or extreme dimension is ignored`() {
        val sourcePlan = plan()
        val source = BuildingPlan(
            levels = listOf(FloorLevel("level-0", 0, 0f, sourcePlan)),
        )

        val result = MetricScaleReviewApplier.apply(
            source = source,
            reviewedLongSideMetersByLevelId = mapOf("level-0" to 79f),
        )

        // 79m is within absolute bounds but exceeds the allowed 5x correction from a 14m plan.
        assertEquals(0, result.correctedLevelCount)
        assertEquals(source, result.building)
    }

    private fun plan(): FloorPlan = FloorPlan(
        widthMeters = 14f,
        depthMeters = 8f,
        walls = listOf(
            WallSegment(
                start = Vec2(-5f, -2f),
                end = Vec2(5f, -2f),
                heightMeters = 3f,
            )
        ),
        doors = listOf(
            DoorOpening(
                center = Vec2(0f, -2f),
                widthMeters = 1f,
                rotationDegrees = 0f,
                confidence = 0.9f,
            )
        ),
        windows = listOf(
            WindowOpening(
                center = Vec2(3f, -2f),
                widthMeters = 1.5f,
                rotationDegrees = 0f,
                sillHeightMeters = 0.9f,
                heightMeters = 1.35f,
                confidence = 0.9f,
            )
        ),
        stairs = listOf(
            Staircase(
                center = Vec2(2f, 1f),
                widthMeters = 1.2f,
                runMeters = 3f,
                rotationDegrees = 90f,
                stepCount = 18,
                floorToFloorHeightMeters = 3.2f,
                confidence = 0.9f,
            )
        ),
        rooms = listOf(
            RoomRegion(
                id = "room-0",
                polygon = listOf(
                    Vec2(-2f, -2f),
                    Vec2(2f, -2f),
                    Vec2(2f, 2f),
                    Vec2(-2f, 2f),
                ),
                label = "صالة",
                confidence = 0.9f,
            )
        ),
        analysisConfidence = 0.8f,
        sourceWidthPx = 1400,
        sourceHeightPx = 800,
        scaleConfidence = 0.28f,
        scaleSource = "geometry_fallback",
    )
}
