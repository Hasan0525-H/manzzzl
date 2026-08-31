package com.manzl.app.render

import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkthroughSceneAssemblerTest {

    @Test
    fun `scene keeps measured walls and facade in separate material batches`() {
        val plan = FloorPlan(
            widthMeters = 6f,
            depthMeters = 6f,
            walls = listOf(
                WallSegment(Vec2(-2f, -2f), Vec2(2f, -2f), heightMeters = 3f),
            ),
            rooms = listOf(
                RoomRegion(
                    id = "living",
                    polygon = listOf(
                        Vec2(-2f, -2f),
                        Vec2(2f, -2f),
                        Vec2(2f, 2f),
                        Vec2(-2f, 2f),
                    ),
                    label = "صالة",
                    confidence = 0.95f,
                )
            ),
            analysisConfidence = 0.92f,
            sourceWidthPx = 1000,
            sourceHeightPx = 1000,
        )

        val scene = WalkthroughSceneAssembler.build(BuildingPlan.singleLevel(plan))

        assertTrue(scene.staticMesh.wallVertices.isNotEmpty())
        assertTrue(scene.facadeMesh.vertices.isNotEmpty())
        assertTrue(scene.facadeMesh.indices.isNotEmpty())
        assertEquals("saudi_contemporary_balanced", scene.primaryDesign.id)
        assertTrue(scene.primaryDesign.palette.stone.r < scene.primaryDesign.palette.wall.r)
    }
}
