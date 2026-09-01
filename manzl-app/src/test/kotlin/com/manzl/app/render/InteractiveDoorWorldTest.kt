package com.manzl.app.render

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.DoorHingeSide
import com.manzl.app.model.DoorOpening
import com.manzl.app.model.DoorSwingSide
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveDoorWorldTest {

    @Test
    fun `known swing door opens smoothly as player approaches`() {
        val world = InteractiveDoorWorld(building(interactiveDoor()))

        val initial = world.angleDegrees("level-0", 0)
        assertEquals(0f, initial ?: -1f, 0.001f)

        repeat(4) {
            world.update(
                currentLevelId = "level-0",
                playerPosition = Vec2(0f, -0.8f),
                deltaSeconds = 0.10f,
            )
        }

        val opened = world.angleDegrees("level-0", 0)
        assertNotNull(opened)
        assertTrue((opened ?: 0f) > 40f)
        assertTrue((opened ?: 100f) < 89f)
    }

    @Test
    fun `door closes only after player clears doorway`() {
        val world = InteractiveDoorWorld(building(interactiveDoor()))
        repeat(8) {
            world.update("level-0", Vec2(0f, -0.8f), 0.10f)
        }
        val openAngle = world.angleDegrees("level-0", 0) ?: 0f
        assertTrue(openAngle > 80f)

        repeat(3) {
            world.update("level-0", Vec2(0f, 0.05f), 0.10f)
        }
        val heldOpen = world.angleDegrees("level-0", 0) ?: 0f
        assertTrue(heldOpen >= openAngle - 0.5f)

        repeat(8) {
            world.update("level-0", Vec2(4f, 4f), 0.10f)
        }
        val closed = world.angleDegrees("level-0", 0) ?: 99f
        assertEquals(0f, closed, 0.01f)
    }

    @Test
    fun `closed physical leaf blocks crossing but open leaf clears centre passage`() {
        val world = InteractiveDoorWorld(building(interactiveDoor()))
        val from = Vec2(0f, -0.60f)
        val target = Vec2(0f, 0.25f)

        val blocked = world.resolveMove("level-0", from, target, radius = 0.27f)
        assertTrue(blocked.z < 0.05f)

        repeat(8) {
            world.update("level-0", Vec2(0f, -0.8f), 0.10f)
        }
        val passed = world.resolveMove("level-0", from, target, radius = 0.27f)
        assertTrue(passed.z > 0.15f)
    }

    @Test
    fun `unknown swing stays non fabricated and has no dynamic leaf`() {
        val unknown = interactiveDoor().copy(
            hingeSide = DoorHingeSide.UNKNOWN,
            swingSide = DoorSwingSide.UNKNOWN,
            swingConfidence = 0f,
        )
        val world = InteractiveDoorWorld(building(unknown))

        assertEquals(0, world.interactiveDoorCount())
        assertTrue(world.poses().isEmpty())
        assertEquals(Vec2(0f, 0.3f), world.resolveMove("level-0", Vec2(0f, -0.3f), Vec2(0f, 0.3f)))
    }

    private fun interactiveDoor() = DoorOpening(
        center = Vec2(0f, 0f),
        widthMeters = 1.0f,
        rotationDegrees = 0f,
        confidence = 0.92f,
        hingeSide = DoorHingeSide.AXIS_START,
        swingSide = DoorSwingSide.POSITIVE_NORMAL,
        swingConfidence = 0.91f,
    )

    private fun building(door: DoorOpening): BuildingPlan {
        val plan = FloorPlan(
            widthMeters = 8f,
            depthMeters = 8f,
            walls = emptyList(),
            doors = listOf(door),
            analysisConfidence = 0.95f,
            sourceWidthPx = 1000,
            sourceHeightPx = 1000,
        )
        return BuildingPlan(
            levels = listOf(
                FloorLevel(
                    id = "level-0",
                    levelIndex = 0,
                    baseElevationMeters = 0f,
                    plan = plan,
                )
            )
        )
    }
}
