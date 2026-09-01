package com.manzl.app.analysis

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Staircase
import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StairLevelLinkerTest {

    @Test
    fun `links stacked stairs even when upper drawing reverses direction`() {
        val lower = level(
            id = "ground",
            index = 0,
            elevation = 0f,
            stairs = listOf(stair(center = Vec2(0f, 0f), rotation = 0f)),
        )
        val upper = level(
            id = "first",
            index = 1,
            elevation = 3.2f,
            stairs = listOf(stair(center = Vec2(0.22f, -0.12f), rotation = 180f)),
        )

        val linked = StairLevelLinker.link(BuildingPlan(listOf(lower, upper)))

        assertEquals(1, linked.stairLinks.size)
        val link = linked.stairLinks.single()
        assertEquals("ground", link.lowerLevelId)
        assertEquals("first", link.upperLevelId)
        assertEquals(0, link.lowerStairIndex)
        assertEquals(0, link.upperStairIndex)
        assertTrue(link.confidence >= 0.58f)
    }

    @Test
    fun `rejects stair on distant side of building`() {
        val lower = level(
            id = "ground",
            index = 0,
            elevation = 0f,
            stairs = listOf(stair(center = Vec2(-3f, 0f), rotation = 90f)),
        )
        val upper = level(
            id = "first",
            index = 1,
            elevation = 3.2f,
            stairs = listOf(stair(center = Vec2(3f, 0f), rotation = 90f)),
        )

        val linked = StairLevelLinker.link(BuildingPlan(listOf(lower, upper)))

        assertTrue(linked.stairLinks.isEmpty())
    }

    @Test
    fun `rejects incompatible stair axis`() {
        val lower = level(
            id = "ground",
            index = 0,
            elevation = 0f,
            stairs = listOf(stair(center = Vec2(0f, 0f), rotation = 0f)),
        )
        val upper = level(
            id = "first",
            index = 1,
            elevation = 3.2f,
            stairs = listOf(stair(center = Vec2(0.1f, 0f), rotation = 90f)),
        )

        val linked = StairLevelLinker.link(BuildingPlan(listOf(lower, upper)))

        assertTrue(linked.stairLinks.isEmpty())
    }

    @Test
    fun `uses one to one matching when multiple stairs exist`() {
        val lower = level(
            id = "ground",
            index = 0,
            elevation = 0f,
            stairs = listOf(
                stair(center = Vec2(-2f, 0f), rotation = 0f),
                stair(center = Vec2(2f, 0f), rotation = 90f),
            ),
        )
        val upper = level(
            id = "first",
            index = 1,
            elevation = 3.2f,
            stairs = listOf(
                stair(center = Vec2(2.08f, 0.06f), rotation = 270f),
                stair(center = Vec2(-1.9f, -0.04f), rotation = 180f),
            ),
        )

        val linked = StairLevelLinker.link(BuildingPlan(listOf(lower, upper)))

        assertEquals(2, linked.stairLinks.size)
        assertEquals(setOf(0, 1), linked.stairLinks.map { it.lowerStairIndex }.toSet())
        assertEquals(setOf(0, 1), linked.stairLinks.map { it.upperStairIndex }.toSet())
    }

    @Test
    fun `low confidence stair cannot create cross level link`() {
        val lower = level(
            id = "ground",
            index = 0,
            elevation = 0f,
            stairs = listOf(stair(center = Vec2(0f, 0f), rotation = 0f, confidence = 0.52f)),
        )
        val upper = level(
            id = "first",
            index = 1,
            elevation = 3.2f,
            stairs = listOf(stair(center = Vec2(0f, 0f), rotation = 0f)),
        )

        val linked = StairLevelLinker.link(BuildingPlan(listOf(lower, upper)))

        assertTrue(linked.stairLinks.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `building rejects duplicate level indices`() {
        BuildingPlan(
            levels = listOf(
                level("ground", 0, 0f, emptyList()),
                level("duplicate", 0, 3.2f, emptyList()),
            )
        )
    }

    private fun level(
        id: String,
        index: Int,
        elevation: Float,
        stairs: List<Staircase>,
    ): FloorLevel = FloorLevel(
        id = id,
        levelIndex = index,
        baseElevationMeters = elevation,
        plan = FloorPlan(
            widthMeters = 10f,
            depthMeters = 10f,
            walls = emptyList(),
            stairs = stairs,
            analysisConfidence = 1f,
            sourceWidthPx = 1200,
            sourceHeightPx = 1200,
        ),
    )

    private fun stair(
        center: Vec2,
        rotation: Float,
        confidence: Float = 0.92f,
    ): Staircase = Staircase(
        center = center,
        widthMeters = 1.10f,
        runMeters = 3.60f,
        rotationDegrees = rotation,
        stepCount = 18,
        floorToFloorHeightMeters = 3.20f,
        confidence = confidence,
    )
}
