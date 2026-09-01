package com.manzl.app.render

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Staircase
import com.manzl.app.model.Vec2
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RoomSurfaceMeshBuilderTest {

    @Test
    fun `stair cuts bounded ceiling opening instead of deleting whole room ceiling`() {
        val room = room(
            id = "stair-hall",
            minX = -4f,
            minZ = -3f,
            maxX = 4f,
            maxZ = 3f,
        )
        val stair = Staircase(
            center = Vec2(0f, 0f),
            widthMeters = 1.10f,
            runMeters = 3.20f,
            rotationDegrees = 90f,
            stepCount = 16,
            floorToFloorHeightMeters = 3.20f,
            confidence = 0.95f,
        )
        val plan = plan(rooms = listOf(room), stairs = listOf(stair))

        val mesh = RoomSurfaceMeshBuilder.build(
            plan = plan,
            ceilingHeightMeters = 3.20f,
            levelIndex = 0,
        )

        val ceilingArea = surfaceArea(mesh.ceilingVertices, mesh.ceilingIndices)
        val fullRoomArea = 48f
        val expectedHole = (3.20f + 0.16f) * (1.10f + 0.12f)
        val expectedCeiling = fullRoomArea - expectedHole

        assertTrue("ceiling disappeared", ceilingArea > fullRoomArea * 0.80f)
        assertTrue("ceiling area=$ceilingArea expected=$expectedCeiling", abs(ceilingArea - expectedCeiling) <= 0.18f)
        assertTrue("stair solids were not emitted", mesh.floorVertices.size > 6 * 6)
    }

    @Test
    fun `trusted upper-floor courtyard remains a vertical floor void`() {
        val courtyard = room(
            id = "courtyard",
            minX = -2f,
            minZ = -2f,
            maxX = 2f,
            maxZ = 2f,
            label = "فناء",
        )
        val plan = plan(rooms = listOf(courtyard))

        val upper = RoomSurfaceMeshBuilder.build(
            plan = plan,
            ceilingHeightMeters = 3.20f,
            levelIndex = 1,
        )

        assertTrue(upper.floorVertices.isEmpty())
        assertTrue(upper.floorIndices.isEmpty())
        assertTrue(upper.ceilingVertices.isEmpty())
        assertTrue(upper.ceilingIndices.isEmpty())
    }

    @Test
    fun `ground courtyard keeps ground surface but never gets a ceiling`() {
        val courtyard = room(
            id = "ground-yard",
            minX = -2f,
            minZ = -2f,
            maxX = 2f,
            maxZ = 2f,
            label = "courtyard",
        )
        val plan = plan(rooms = listOf(courtyard))

        val ground = RoomSurfaceMeshBuilder.build(
            plan = plan,
            ceilingHeightMeters = 3.20f,
            levelIndex = 0,
        )

        assertTrue(ground.floorVertices.isNotEmpty())
        assertTrue(abs(surfaceArea(ground.floorVertices, ground.floorIndices) - 16f) <= 0.01f)
        assertTrue(ground.ceilingVertices.isEmpty())
        assertTrue(ground.ceilingIndices.isEmpty())
    }

    private fun plan(
        rooms: List<RoomRegion>,
        stairs: List<Staircase> = emptyList(),
    ) = FloorPlan(
        widthMeters = 10f,
        depthMeters = 8f,
        walls = emptyList(),
        rooms = rooms,
        stairs = stairs,
        analysisConfidence = 1f,
        sourceWidthPx = 1200,
        sourceHeightPx = 960,
    )

    private fun room(
        id: String,
        minX: Float,
        minZ: Float,
        maxX: Float,
        maxZ: Float,
        label: String? = null,
    ) = RoomRegion(
        id = id,
        polygon = listOf(
            Vec2(minX, minZ),
            Vec2(maxX, minZ),
            Vec2(maxX, maxZ),
            Vec2(minX, maxZ),
        ),
        label = label,
        confidence = 0.96f,
    )

    private fun surfaceArea(vertices: FloatArray, indices: IntArray): Float {
        if (vertices.isEmpty() || indices.isEmpty()) return 0f
        val points = vertices.toList().chunked(6).map { Vec2(it[0], it[2]) }
        var area = 0f
        for (triangle in indices.toList().chunked(3)) {
            if (triangle.size != 3) continue
            val a = points[triangle[0]]
            val b = points[triangle[1]]
            val c = points[triangle[2]]
            area += abs(
                (b.x - a.x) * (c.z - a.z) -
                    (b.z - a.z) * (c.x - a.x)
            ) * 0.5f
        }
        return area
    }
}
