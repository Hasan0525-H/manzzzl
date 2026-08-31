package com.manzl.app.render

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Staircase
import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StairTraversalResolverTest {

    private val stair = Staircase(
        center = Vec2(0f, 0f),
        widthMeters = 1.10f,
        runMeters = 3.60f,
        rotationDegrees = 0f,
        stepCount = 18,
        floorToFloorHeightMeters = 3.15f,
        confidence = 0.92f,
    )

    private fun resolver(staircase: Staircase = stair): StairTraversalResolver = StairTraversalResolver(
        FloorPlan(
            widthMeters = 8f,
            depthMeters = 8f,
            walls = emptyList(),
            stairs = listOf(staircase),
            analysisConfidence = 1f,
            sourceWidthPx = 1000,
            sourceHeightPx = 1000,
        )
    )

    @Test
    fun `walker climbs adjacent treads without vertical teleport`() {
        val resolver = resolver()
        val treadDepth = stair.runMeters / stair.stepCount
        val rise = stair.floorToFloorHeightMeters / stair.stepCount
        var position = Vec2(-stair.runMeters / 2f - 0.03f, 0f)
        var elevation = 0f

        repeat(stair.stepCount) { index ->
            val target = Vec2(
                x = -stair.runMeters / 2f + treadDepth * (index + 0.5f),
                z = 0f,
            )
            val result = resolver.resolveMove(position, target, elevation)
            assertFalse("tread $index unexpectedly blocked", result.blockedByVerticalTransition)
            assertEquals(rise * (index + 1), result.elevationMeters, 0.0001f)
            position = result.position
            elevation = result.elevationMeters
        }
    }

    @Test
    fun `side entry onto a high tread is blocked`() {
        val resolver = resolver()
        val from = Vec2(0f, stair.widthMeters)
        val to = Vec2(0f, 0f)

        val result = resolver.resolveMove(from, to, currentElevationMeters = 0f)

        assertTrue(result.blockedByVerticalTransition)
        assertEquals(from, result.position)
        assertEquals(0f, result.elevationMeters, 0f)
    }

    @Test
    fun `walker can step off the first tread onto lower floor`() {
        val resolver = resolver()
        val treadDepth = stair.runMeters / stair.stepCount
        val firstTread = Vec2(-stair.runMeters / 2f + treadDepth * 0.5f, 0f)
        val firstRise = stair.floorToFloorHeightMeters / stair.stepCount
        val floorPoint = Vec2(-stair.runMeters / 2f - 0.05f, 0f)

        val result = resolver.resolveMove(firstTread, floorPoint, firstRise)

        assertFalse(result.blockedByVerticalTransition)
        assertEquals(0f, result.elevationMeters, 0f)
    }

    @Test
    fun `walker cannot leave the top into unlinked upper floor`() {
        val resolver = resolver()
        val treadDepth = stair.runMeters / stair.stepCount
        val topTread = Vec2(stair.runMeters / 2f - treadDepth * 0.5f, 0f)
        val outsideTop = Vec2(stair.runMeters / 2f + 0.08f, 0f)

        val result = resolver.resolveMove(
            from = topTread,
            to = outsideTop,
            currentElevationMeters = stair.floorToFloorHeightMeters,
        )

        assertTrue(result.blockedByVerticalTransition)
        assertEquals(topTread, result.position)
        assertEquals(stair.floorToFloorHeightMeters, result.elevationMeters, 0f)
    }

    @Test
    fun `rotation ninety degrees maps run to z axis`() {
        val rotated = stair.copy(rotationDegrees = 90f)
        val resolver = resolver(rotated)
        val treadDepth = rotated.runMeters / rotated.stepCount
        val firstTread = Vec2(0f, -rotated.runMeters / 2f + treadDepth * 0.5f)
        val expectedRise = rotated.floorToFloorHeightMeters / rotated.stepCount

        assertEquals(expectedRise, resolver.elevationAt(firstTread), 0.0001f)
        assertEquals(0f, resolver.elevationAt(Vec2(rotated.widthMeters, 0f)), 0f)
    }

    @Test
    fun `implausibly tall risers are excluded from traversal`() {
        val unsafe = stair.copy(stepCount = 8, floorToFloorHeightMeters = 3.2f)
        val resolver = resolver(unsafe)

        assertEquals(0f, resolver.elevationAt(Vec2(0f, 0f)), 0f)
    }
}
