package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Staircase
import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildingPlanAssemblerTest {

    @Test
    fun `ordered plans use measured stair height and link matching stairs`() {
        val stair = Staircase(
            center = Vec2(0.4f, -0.3f),
            widthMeters = 1.15f,
            runMeters = 3.4f,
            rotationDegrees = 90f,
            stepCount = 18,
            floorToFloorHeightMeters = 3.35f,
            confidence = 0.92f,
        )
        val ground = plan(stairs = listOf(stair))
        val first = plan(stairs = listOf(stair.copy(confidence = 0.88f)))

        val building = BuildingPlanAssembler.assemble(listOf(ground, first))

        assertEquals(2, building.levels.size)
        assertEquals(0f, building.levels[0].baseElevationMeters, 0.001f)
        assertEquals(3.35f, building.levels[1].baseElevationMeters, 0.001f)
        assertEquals(1, building.stairLinks.size)
        assertEquals("level-0", building.stairLinks.single().lowerLevelId)
        assertEquals("level-1", building.stairLinks.single().upperLevelId)
        assertTrue(building.stairLinks.single().confidence >= 0.58f)
    }

    @Test
    fun `missing trustworthy stair height falls back without changing plans`() {
        val lower = plan(stairs = emptyList())
        val upper = plan(stairs = emptyList())

        val building = BuildingPlanAssembler.assemble(listOf(lower, upper))

        assertEquals(3.20f, building.levels[1].baseElevationMeters, 0.001f)
        assertTrue(building.stairLinks.isEmpty())
        assertEquals(lower, building.levels[0].plan)
        assertEquals(upper, building.levels[1].plan)
    }

    private fun plan(stairs: List<Staircase>) = FloorPlan(
        widthMeters = 12f,
        depthMeters = 10f,
        walls = emptyList(),
        stairs = stairs,
        analysisConfidence = 0.95f,
        sourceWidthPx = 1200,
        sourceHeightPx = 1000,
        scaleConfidence = 0.9f,
        scaleSource = "test",
    )
}
