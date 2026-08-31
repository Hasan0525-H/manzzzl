package com.manzl.app.render

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.FloorPlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildingMeshBuilderTest {

    @Test
    fun `two floor meshes are stacked at declared elevations and indices stay valid`() {
        val ground = level(id = "ground", index = 0, elevation = 0f)
        val first = level(id = "first", index = 1, elevation = 3.2f)

        val mesh = BuildingMeshBuilder.build(BuildingPlan(levels = listOf(first, ground)))

        assertEquals(2 * 4 * 6, mesh.floorVertices.size)
        assertEquals(2 * 6, mesh.floorIndices.size)
        assertArrayEquals(
            intArrayOf(0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7),
            mesh.floorIndices,
        )

        val vertices = mesh.floorVertices.toList().chunked(6)
        assertTrue(vertices.take(4).all { it[1] == 0f })
        assertTrue(vertices.drop(4).all { kotlin.math.abs(it[1] - 3.2f) < 0.0001f })
        assertTrue(mesh.floorVertices.all { it.isFinite() })
    }

    @Test
    fun `single level building remains equivalent to local floor mesh`() {
        val plan = plan()
        val single = BuildingMeshBuilder.build(BuildingPlan.singleLevel(plan))
        val local = HouseMeshBuilder.build(plan)

        assertArrayEquals(local.floorVertices, single.floorVertices, 0f)
        assertArrayEquals(local.floorIndices, single.floorIndices)
        assertArrayEquals(local.wallVertices, single.wallVertices, 0f)
        assertArrayEquals(local.wallIndices, single.wallIndices)
    }

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
        analysisConfidence = 1f,
        sourceWidthPx = 1000,
        sourceHeightPx = 800,
    )
}
