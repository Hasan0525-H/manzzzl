package com.manzl.app.render

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.FloorLevel
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildingFacadeMeshBuilderTest {

    @Test
    fun `upper floor facade is translated to declared elevation`() {
        val plan = floorPlan()
        val building = BuildingPlan(
            levels = listOf(
                FloorLevel("level-0", 0, 0f, plan),
                FloorLevel("level-1", 1, 3.2f, plan),
            )
        )

        val mesh = BuildingFacadeMeshBuilder.build(building)
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var i = 0
        while (i + 5 < mesh.vertices.size) {
            minY = minOf(minY, mesh.vertices[i + 1])
            maxY = maxOf(maxY, mesh.vertices[i + 1])
            i += 6
        }

        assertTrue(mesh.vertices.isNotEmpty())
        assertTrue(minY <= 0.01f)
        assertTrue(maxY > 6.0f)
    }

    private fun floorPlan(): FloorPlan {
        val wall = WallSegment(Vec2(-2f, -2f), Vec2(2f, -2f), heightMeters = 3f)
        val room = RoomRegion(
            id = "living",
            polygon = listOf(Vec2(-2f, -2f), Vec2(2f, -2f), Vec2(2f, 2f), Vec2(-2f, 2f)),
            label = "صالة",
            confidence = 0.95f,
        )
        return FloorPlan(
            widthMeters = 6f,
            depthMeters = 6f,
            walls = listOf(wall),
            rooms = listOf(room),
            analysisConfidence = 0.9f,
            sourceWidthPx = 1000,
            sourceHeightPx = 1000,
        )
    }
}
