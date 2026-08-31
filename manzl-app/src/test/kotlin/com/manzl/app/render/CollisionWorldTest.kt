package com.manzl.app.render

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertTrue
import org.junit.Test

class CollisionWorldTest {

    @Test
    fun `solid wall blocks player and prevents tunnelling`() {
        val world = CollisionWorld(
            plan(
                walls = listOf(
                    WallSegment(start = Vec2(0f, -2f), end = Vec2(0f, 2f), thicknessMeters = 0.18f),
                )
            )
        )

        val result = world.move(
            position = Vec2(-1.2f, 0f),
            deltaX = 2.4f,
            deltaZ = 0f,
            radius = CollisionWorld.DEFAULT_PLAYER_RADIUS,
        )

        assertTrue("player crossed a solid wall: ${result.x}", result.x < -0.30f)
    }

    @Test
    fun `geometric wall gap remains walkable`() {
        val world = CollisionWorld(
            plan(
                walls = listOf(
                    WallSegment(start = Vec2(0f, -3f), end = Vec2(0f, -0.65f)),
                    WallSegment(start = Vec2(0f, 0.65f), end = Vec2(0f, 3f)),
                )
            )
        )

        val result = world.move(
            position = Vec2(-1.1f, 0f),
            deltaX = 2.2f,
            deltaZ = 0f,
            radius = CollisionWorld.DEFAULT_PLAYER_RADIUS,
        )

        assertTrue("player could not pass the doorway gap: ${result.x}", result.x > 0.6f)
    }

    @Test
    fun `door metadata cuts a traversable passage through a continuous wall`() {
        val wall = WallSegment(start = Vec2(0f, -3f), end = Vec2(0f, 3f), thicknessMeters = 0.18f)
        val world = CollisionWorld(
            plan(
                walls = listOf(wall),
                doors = listOf(
                    DoorOpening(
                        center = Vec2(0f, 0f),
                        widthMeters = 1.05f,
                        rotationDegrees = 0f,
                        confidence = 0.95f,
                    )
                ),
            )
        )

        val result = world.move(
            position = Vec2(-1.1f, 0f),
            deltaX = 2.2f,
            deltaZ = 0f,
            radius = CollisionWorld.DEFAULT_PLAYER_RADIUS,
        )

        assertTrue("door opening did not allow passage: ${result.x}", result.x > 0.6f)
    }

    @Test
    fun `spawn point is never inside a wall`() {
        val world = CollisionWorld(
            plan(
                walls = listOf(
                    WallSegment(start = Vec2(-2f, 0f), end = Vec2(2f, 0f), thicknessMeters = 0.3f),
                )
            )
        )

        val spawn = world.findSpawn()

        assertTrue("spawn is colliding at $spawn", world.isClear(spawn))
    }

    private fun plan(
        walls: List<WallSegment>,
        doors: List<DoorOpening> = emptyList(),
    ): FloorPlan = FloorPlan(
        widthMeters = 8f,
        depthMeters = 8f,
        walls = walls,
        doors = doors,
        analysisConfidence = 1f,
        sourceWidthPx = 1000,
        sourceHeightPx = 1000,
    )
}
