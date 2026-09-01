package com.manzl.app.render

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.DoorHingeSide
import com.manzl.app.model.DoorOpening
import com.manzl.app.model.DoorSwingSide
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BuildingMeshBuilderTest {

    @Test
    fun `two floor verified surfaces are stacked at declared elevations and indices stay valid`() {
        val ground = level(id = "ground", index = 0, elevation = 0f)
        val first = level(id = "first", index = 1, elevation = 3.2f)

        val mesh = BuildingMeshBuilder.build(BuildingPlan(levels = listOf(first, ground)))

        // One rectangular room -> two independent triangles -> six vertices per level.
        assertEquals(2 * 6 * 6, mesh.floorVertices.size)
        assertEquals(2 * 6, mesh.floorIndices.size)
        assertTrue(mesh.floorIndices.all { it in 0 until mesh.floorVertices.size / 6 })

        val vertices = mesh.floorVertices.toList().chunked(6)
        assertTrue(vertices.take(6).all { it[1] == 0f })
        assertTrue(vertices.drop(6).all { abs(it[1] - 3.2f) < 0.0001f })
        assertTrue(mesh.floorVertices.all { it.isFinite() })
    }

    @Test
    fun `building path preserves wall mesh while replacing legacy floor fallback`() {
        val plan = plan()
        val single = BuildingMeshBuilder.build(BuildingPlan.singleLevel(plan))
        val local = HouseMeshBuilder.build(plan)

        assertTrue(single.floorVertices.isNotEmpty())
        assertEquals(local.wallVertices.toList(), single.wallVertices.toList())
        assertEquals(local.wallIndices.toList(), single.wallIndices.toList())
    }

    @Test
    fun `known interactive door leaf is excluded from static building mesh`() {
        val door = DoorOpening(
            center = Vec2(0f, 0f),
            widthMeters = 1f,
            rotationDegrees = 0f,
            confidence = 0.94f,
            hingeSide = DoorHingeSide.AXIS_START,
            swingSide = DoorSwingSide.POSITIVE_NORMAL,
            swingConfidence = 0.92f,
        )
        val plan = plan().copy(doors = listOf(door))

        val staticBuilding = BuildingMeshBuilder.build(BuildingPlan.singleLevel(plan))
        val localWithFixedLeaf = HouseMeshBuilder.build(plan)

        assertEquals(3 * 6 * 4 * 6, staticBuilding.trimVertices.size)
        assertEquals(4 * 6 * 4 * 6, localWithFixedLeaf.trimVertices.size)
    }

    @Test
    fun `independent shaft face is omitted from floor and ceiling surfaces`() {
        val left = room("left", -4f, -3f, -1f, 3f)
        val right = room("right", 1f, -3f, 4f, 3f)
        val shaft = room("shaft", -0.7f, -1f, 0.7f, 1f, label = "shaft")
        val shaftPlan = FloorPlan(
            widthMeters = 10f,
            depthMeters = 8f,
            walls = emptyList(),
            rooms = listOf(left, right, shaft),
            analysisConfidence = 1f,
            sourceWidthPx = 1000,
            sourceHeightPx = 800,
        )

        val mesh = BuildingMeshBuilder.build(BuildingPlan.singleLevel(shaftPlan))
        val floorVertices = mesh.floorVertices.toList().chunked(6)

        assertEquals(2 * 6 * 6, mesh.floorVertices.size)
        assertTrue("shaft received floor geometry", floorVertices.none { it[0] > -1f && it[0] < 1f })
    }

    @Test
    fun `nested shaft is subtracted from enclosing room instead of blocking or filling`() {
        val outer = room("hall", -4f, -3f, 4f, 3f)
        val shaft = room("shaft", -1f, -1f, 1f, 1f, label = "service shaft")
        val shaftPlan = FloorPlan(
            widthMeters = 10f,
            depthMeters = 8f,
            walls = emptyList(),
            rooms = listOf(outer, shaft),
            analysisConfidence = 1f,
            sourceWidthPx = 1000,
            sourceHeightPx = 800,
        )

        val mesh = BuildingMeshBuilder.build(BuildingPlan.singleLevel(shaftPlan))
        val triangles = meshTriangles(mesh.floorVertices, mesh.floorIndices)
        val area = triangles.sumOf { triangleArea(it).toDouble() }.toFloat()

        assertTrue("area=$area", abs(area - 44f) <= 0.14f)
        assertTrue(triangles.none { triangle ->
            val x = (triangle[0].first + triangle[1].first + triangle[2].first) / 3f
            val z = (triangle[0].second + triangle[1].second + triangle[2].second) / 3f
            x > -0.98f && x < 0.98f && z > -0.98f && z < 0.98f
        })
    }

    private fun meshTriangles(vertices: FloatArray, indices: IntArray): List<List<Pair<Float, Float>>> {
        val points = vertices.toList().chunked(6).map { it[0] to it[2] }
        return indices.toList().chunked(3).map { tri -> tri.map { points[it] } }
    }

    private fun triangleArea(triangle: List<Pair<Float, Float>>): Float {
        val a = triangle[0]
        val b = triangle[1]
        val c = triangle[2]
        return abs((b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first)) * 0.5f
    }

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
        confidence = 0.95f,
    )

    private fun level(id: String, index: Int, elevation: Float): FloorLevel = FloorLevel(
        id = id,
        levelIndex = index,
        baseElevationMeters = elevation,
        plan = plan(),
    )

    private fun plan(): FloorPlan = FloorPlan(
        widthMeters = 6f,
        depthMeters = 5f,
        walls = emptyList(),
        rooms = listOf(room("main", -2f, -1.5f, 2f, 1.5f)),
        analysisConfidence = 1f,
        sourceWidthPx = 1000,
        sourceHeightPx = 800,
    )
}
