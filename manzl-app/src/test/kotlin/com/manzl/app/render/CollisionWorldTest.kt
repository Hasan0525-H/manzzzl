package com.manzl.app.render

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.StructuralColumn
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import com.manzl.app.model.WindowOpening
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
    fun `verified structural column blocks player and prevents tunnelling`() {
        val world = CollisionWorld(
            plan(
                walls = emptyList(),
                columns = listOf(
                    StructuralColumn(
                        center = Vec2(0f, 0f),
                        widthMeters = 0.60f,
                        depthMeters = 0.50f,
                        rotationDegrees = 28f,
                        confidence = 0.95f,
                    )
                ),
            )
        )

        assertTrue("column centre should be blocked", !world.isClear(Vec2(0f, 0f)))
        val result = world.move(
            position = Vec2(-1.3f, 0f),
            deltaX = 2.6f,
            deltaZ = 0f,
            radius = CollisionWorld.DEFAULT_PLAYER_RADIUS,
        )

        assertTrue("player crossed a structural column: $result", result.x < 0.05f)
        assertTrue("resolved position still overlaps the structural column: $result", world.isClear(result))
    }

    @Test
    fun `low confidence column proposal is not promoted into collision`() {
        val world = CollisionWorld(
            plan(
                walls = emptyList(),
                columns = listOf(
                    StructuralColumn(
                        center = Vec2(0f, 0f),
                        widthMeters = 0.55f,
                        depthMeters = 0.55f,
                        confidence = 0.50f,
                    )
                ),
            )
        )

        assertTrue("unverified column should not become physical geometry", world.isClear(Vec2(0f, 0f), 0.10f))
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
    fun `same axis door metadata permits its continuous wall span`() {
        val wall = WallSegment(start = Vec2(0f, -3f), end = Vec2(0f, 3f), thicknessMeters = 0.18f)
        val world = CollisionWorld(
            plan(
                walls = listOf(wall),
                doors = listOf(
                    DoorOpening(
                        center = Vec2(0f, 0f),
                        widthMeters = 1.05f,
                        rotationDegrees = 90f,
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
    fun `nearby perpendicular door cannot punch passage through wrong wall`() {
        val wall = WallSegment(start = Vec2(0f, -3f), end = Vec2(0f, 3f), thicknessMeters = 0.18f)
        val world = CollisionWorld(
            plan(
                walls = listOf(wall),
                doors = listOf(
                    DoorOpening(
                        center = Vec2(0f, 0f),
                        widthMeters = 1.05f,
                        rotationDegrees = 0f,
                        confidence = 0.98f,
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

        assertTrue("crossing door incorrectly opened the wall: ${result.x}", result.x < -0.25f)
    }

    @Test
    fun `window metadata closes a raster wall gap for player collision`() {
        val world = CollisionWorld(
            plan(
                walls = listOf(
                    WallSegment(start = Vec2(0f, -3f), end = Vec2(0f, -0.75f)),
                    WallSegment(start = Vec2(0f, 0.75f), end = Vec2(0f, 3f)),
                ),
                windows = listOf(
                    WindowOpening(
                        center = Vec2(0f, 0f),
                        widthMeters = 1.5f,
                        rotationDegrees = 90f,
                        confidence = 0.92f,
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

        assertTrue("player walked through a detected window: ${result.x}", result.x < -0.25f)
    }

    @Test
    fun `spawn point is never inside a wall window or verified column barrier`() {
        val world = CollisionWorld(
            plan(
                walls = listOf(
                    WallSegment(start = Vec2(-2f, 0f), end = Vec2(2f, 0f), thicknessMeters = 0.3f),
                ),
                columns = listOf(
                    StructuralColumn(
                        center = Vec2(0.6f, -1f),
                        widthMeters = 0.45f,
                        depthMeters = 0.45f,
                        confidence = 0.93f,
                    )
                ),
                windows = listOf(
                    WindowOpening(
                        center = Vec2(0f, 1f),
                        widthMeters = 1.2f,
                        rotationDegrees = 0f,
                        confidence = 0.9f,
                    )
                ),
            )
        )

        val spawn = world.findSpawn()

        assertTrue("spawn is colliding at $spawn", world.isClear(spawn))
    }

    @Test
    fun `trusted room geometry wins over clear drawing centre for spawn`() {
        val room = RoomRegion(
            id = "living-east",
            polygon = rectangle(2.2f, -1.6f, 5.2f, 1.6f),
            label = "صالة",
            confidence = 0.95f,
        )
        val world = CollisionWorld(
            plan(
                walls = emptyList(),
                rooms = listOf(room),
                widthMeters = 12f,
                depthMeters = 8f,
            )
        )

        val spawn = world.findSpawn()

        assertTrue("spawn ignored trusted room geometry: $spawn", spawn.x in 2.2f..5.2f)
        assertTrue("room-derived spawn is not clear: $spawn", world.isClear(spawn))
    }

    @Test
    fun `entrance room is preferred over service room when both are safe`() {
        val bathroom = RoomRegion(
            id = "bath",
            polygon = rectangle(-4.5f, -1.5f, -1.5f, 1.5f),
            label = "حمام",
            confidence = 0.97f,
        )
        val entrance = RoomRegion(
            id = "entry",
            polygon = rectangle(1.5f, -1.5f, 4.5f, 1.5f),
            label = "مدخل",
            confidence = 0.90f,
        )
        val world = CollisionWorld(
            plan(
                walls = emptyList(),
                rooms = listOf(bathroom, entrance),
                widthMeters = 12f,
                depthMeters = 8f,
            )
        )

        val spawn = world.findSpawn()

        assertTrue("tour started in service room instead of entrance: $spawn", spawn.x > 1.4f)
    }

    private fun rectangle(minX: Float, minZ: Float, maxX: Float, maxZ: Float): List<Vec2> = listOf(
        Vec2(minX, minZ),
        Vec2(maxX, minZ),
        Vec2(maxX, maxZ),
        Vec2(minX, maxZ),
    )

    private fun plan(
        walls: List<WallSegment>,
        columns: List<StructuralColumn> = emptyList(),
        doors: List<DoorOpening> = emptyList(),
        windows: List<WindowOpening> = emptyList(),
        rooms: List<RoomRegion> = emptyList(),
        widthMeters: Float = 8f,
        depthMeters: Float = 8f,
    ): FloorPlan = FloorPlan(
        widthMeters = widthMeters,
        depthMeters = depthMeters,
        walls = walls,
        columns = columns,
        doors = doors,
        windows = windows,
        rooms = rooms,
        analysisConfidence = 1f,
        sourceWidthPx = 1000,
        sourceHeightPx = 1000,
    )
}
