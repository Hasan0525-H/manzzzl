package com.manzl.app.render

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.StairLevelLink
import com.manzl.app.model.Staircase
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiLevelWalkWorldTest {

    private val stair = Staircase(
        center = Vec2(0f, 0f),
        widthMeters = 1.10f,
        runMeters = 3.60f,
        rotationDegrees = 0f,
        stepCount = 18,
        floorToFloorHeightMeters = 3.20f,
        confidence = 0.94f,
    )

    @Test
    fun `walks up linked stair and activates upper floor at landing`() {
        val world = world(upperElevation = 3.20f)
        var level = "ground"
        var position = Vec2(-1.90f, 0f)
        var elevation = 0f
        val treadDepth = stair.runMeters / stair.stepCount

        repeat(stair.stepCount) { index ->
            val target = Vec2(-stair.runMeters / 2f + treadDepth * (index + 0.5f), 0f)
            val move = world.move(
                levelId = level,
                position = position,
                globalElevationMeters = elevation,
                deltaX = target.x - position.x,
                deltaZ = 0f,
            )
            assertFalse("tread $index blocked", move.blocked)
            assertEquals("ground", move.levelId)
            position = move.position
            elevation = move.globalElevationMeters
        }

        assertEquals(3.20f, elevation, 0.001f)
        val landing = world.move(
            levelId = level,
            position = position,
            globalElevationMeters = elevation,
            deltaX = 0.20f,
            deltaZ = 0f,
        )

        assertFalse(landing.blocked)
        assertEquals("first", landing.levelId)
        assertEquals(LevelTransition.UP, landing.levelTransition)
        assertEquals(3.20f, landing.globalElevationMeters, 0.001f)
        assertEquals(1.90f, landing.position.x, 0.001f)
    }

    @Test
    fun `upper landing can transition back onto lower stair and descend`() {
        val world = world(upperElevation = 3.20f)
        val ontoTopTread = world.move(
            levelId = "first",
            position = Vec2(1.90f, 0f),
            globalElevationMeters = 3.20f,
            deltaX = -0.20f,
            deltaZ = 0f,
        )

        assertFalse(ontoTopTread.blocked)
        assertEquals("ground", ontoTopTread.levelId)
        assertEquals(LevelTransition.DOWN, ontoTopTread.levelTransition)
        assertEquals(3.20f, ontoTopTread.globalElevationMeters, 0.001f)

        val nextTread = world.move(
            levelId = ontoTopTread.levelId,
            position = ontoTopTread.position,
            globalElevationMeters = ontoTopTread.globalElevationMeters,
            deltaX = -0.20f,
            deltaZ = 0f,
        )
        assertFalse(nextTread.blocked)
        assertEquals("ground", nextTread.levelId)
        assertTrue(nextTread.globalElevationMeters < 3.20f)
        assertNull(nextTread.levelTransition)
    }

    @Test
    fun `mismatched upper elevation cannot silently connect floors`() {
        val world = world(upperElevation = 4.10f)
        val result = world.move(
            levelId = "ground",
            position = Vec2(1.70f, 0f),
            globalElevationMeters = 3.20f,
            deltaX = 0.20f,
            deltaZ = 0f,
        )

        assertTrue(result.blocked)
        assertEquals("ground", result.levelId)
        assertEquals(Vec2(1.70f, 0f), result.position)
    }

    @Test
    fun `blocked upper landing prevents cross floor transition`() {
        val blockingWall = WallSegment(
            start = Vec2(1.90f, -1f),
            end = Vec2(1.90f, 1f),
            thicknessMeters = 0.20f,
        )
        val world = world(upperElevation = 3.20f, upperWalls = listOf(blockingWall))
        val result = world.move(
            levelId = "ground",
            position = Vec2(1.70f, 0f),
            globalElevationMeters = 3.20f,
            deltaX = 0.20f,
            deltaZ = 0f,
        )

        assertTrue(result.blocked)
        assertEquals("ground", result.levelId)
        assertEquals(Vec2(1.70f, 0f), result.position)
    }

    @Test
    fun `single level world preserves ordinary ground movement`() {
        val plan = floorPlan(stairs = emptyList(), walls = emptyList())
        val world = MultiLevelWalkWorld(BuildingPlan.singleLevel(plan))
        val spawn = world.findInitialSpawn()
        val move = world.move(
            levelId = spawn.levelId,
            position = spawn.position,
            globalElevationMeters = spawn.globalElevationMeters,
            deltaX = 0.45f,
            deltaZ = 0.20f,
        )

        assertFalse(move.blocked)
        assertEquals("level-0", move.levelId)
        assertEquals(0f, move.globalElevationMeters, 0f)
        assertEquals(0.45f, move.position.x, 0.001f)
        assertEquals(0.20f, move.position.z, 0.001f)
    }

    private fun world(
        upperElevation: Float,
        upperWalls: List<WallSegment> = emptyList(),
    ): MultiLevelWalkWorld {
        val ground = FloorLevel(
            id = "ground",
            levelIndex = 0,
            baseElevationMeters = 0f,
            plan = floorPlan(stairs = listOf(stair), walls = emptyList()),
        )
        val first = FloorLevel(
            id = "first",
            levelIndex = 1,
            baseElevationMeters = upperElevation,
            plan = floorPlan(stairs = listOf(stair.copy(rotationDegrees = 180f)), walls = upperWalls),
        )
        return MultiLevelWalkWorld(
            BuildingPlan(
                levels = listOf(ground, first),
                stairLinks = listOf(
                    StairLevelLink(
                        lowerLevelId = "ground",
                        upperLevelId = "first",
                        lowerStairIndex = 0,
                        upperStairIndex = 0,
                        confidence = 0.92f,
                    )
                ),
            )
        )
    }

    private fun floorPlan(
        stairs: List<Staircase>,
        walls: List<WallSegment>,
    ): FloorPlan = FloorPlan(
        widthMeters = 10f,
        depthMeters = 10f,
        walls = walls,
        stairs = stairs,
        analysisConfidence = 1f,
        sourceWidthPx = 1200,
        sourceHeightPx = 1200,
    )
}
