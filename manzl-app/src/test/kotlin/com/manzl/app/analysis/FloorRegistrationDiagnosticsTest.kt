package com.manzl.app.analysis

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.FloorRegistrationEvidence
import com.manzl.app.model.FloorRegistrationStatus
import com.manzl.app.model.Staircase
import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorRegistrationDiagnosticsTest {

    @Test
    fun `matching stair shafts report aligned floors`() {
        val stair = stair(center = Vec2(0.4f, -0.2f))
        val building = building(
            lower = plan(stairs = listOf(stair)),
            upper = plan(stairs = listOf(stair.copy(confidence = 0.88f))),
        )

        val diagnostic = FloorRegistrationDiagnostics.diagnose(building).single()

        assertEquals(FloorRegistrationStatus.ALIGNED, diagnostic.status)
        assertEquals(FloorRegistrationEvidence.STAIR_SHAFT, diagnostic.evidence)
        assertEquals(0f, diagnostic.suggestedOffsetXMeters, 0.001f)
        assertEquals(0f, diagnostic.suggestedOffsetZMeters, 0.001f)
        assertTrue(diagnostic.confidence >= 0.70f)
    }

    @Test
    fun `same stair shaft with shifted crop proposes reviewable translation without applying it`() {
        val lowerStair = stair(center = Vec2(0.4f, -0.2f))
        val upperStair = stair(center = Vec2(2.0f, 0.7f)).copy(confidence = 0.89f)
        val lowerPlan = plan(stairs = listOf(lowerStair))
        val upperPlan = plan(stairs = listOf(upperStair))
        val building = building(lowerPlan, upperPlan)

        val diagnostic = FloorRegistrationDiagnostics.diagnose(building).single()

        assertEquals(FloorRegistrationStatus.REVIEW_REQUIRED, diagnostic.status)
        assertEquals(FloorRegistrationEvidence.STAIR_SHAFT, diagnostic.evidence)
        assertEquals(-1.6f, diagnostic.suggestedOffsetXMeters, 0.001f)
        assertEquals(-0.9f, diagnostic.suggestedOffsetZMeters, 0.001f)
        // Diagnostics never mutate the source floor geometry.
        assertEquals(Vec2(2.0f, 0.7f), building.levels[1].plan.stairs.single().center)
    }

    @Test
    fun `large footprint disagreement without shared anchor remains unresolved`() {
        val lower = plan(stairs = emptyList(), width = 12f, depth = 10f, sourceWidth = 1200, sourceHeight = 1000)
        val upper = plan(stairs = emptyList(), width = 7f, depth = 16f, sourceWidth = 700, sourceHeight = 1600)

        val diagnostic = FloorRegistrationDiagnostics.diagnose(building(lower, upper)).single()

        assertEquals(FloorRegistrationStatus.UNRESOLVED, diagnostic.status)
        assertTrue(diagnostic.confidence < 0.50f)
        assertEquals(0f, diagnostic.suggestedOffsetXMeters, 0.001f)
        assertEquals(0f, diagnostic.suggestedOffsetZMeters, 0.001f)
    }

    private fun building(lower: FloorPlan, upper: FloorPlan) = BuildingPlan(
        levels = listOf(
            FloorLevel("level-0", 0, 0f, lower),
            FloorLevel("level-1", 1, 3.2f, upper),
        )
    )

    private fun stair(center: Vec2) = Staircase(
        center = center,
        widthMeters = 1.15f,
        runMeters = 3.4f,
        rotationDegrees = 90f,
        stepCount = 18,
        floorToFloorHeightMeters = 3.2f,
        confidence = 0.92f,
    )

    private fun plan(
        stairs: List<Staircase>,
        width: Float = 12f,
        depth: Float = 10f,
        sourceWidth: Int = 1200,
        sourceHeight: Int = 1000,
    ) = FloorPlan(
        widthMeters = width,
        depthMeters = depth,
        walls = emptyList(),
        stairs = stairs,
        analysisConfidence = 0.95f,
        sourceWidthPx = sourceWidth,
        sourceHeightPx = sourceHeight,
        scaleConfidence = 0.9f,
        scaleSource = "test",
    )
}
