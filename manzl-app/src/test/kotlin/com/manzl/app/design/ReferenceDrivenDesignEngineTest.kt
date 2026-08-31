package com.manzl.app.design

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceDrivenDesignEngineTest {

    @Test
    fun `design synthesis is deterministic and does not alter plan geometry`() {
        val walls = listOf(
            WallSegment(Vec2(-3f, -2f), Vec2(3f, -2f)),
            WallSegment(Vec2(3f, -2f), Vec2(3f, 2f)),
            WallSegment(Vec2(3f, 2f), Vec2(-3f, 2f)),
            WallSegment(Vec2(-3f, 2f), Vec2(-3f, -2f)),
        )
        val plan = FloorPlan(
            widthMeters = 6f,
            depthMeters = 4f,
            walls = walls,
            analysisConfidence = 0.92f,
            sourceWidthPx = 1200,
            sourceHeightPx = 800,
        )

        val first = ReferenceDrivenDesignEngine.synthesize(plan)
        val second = ReferenceDrivenDesignEngine.synthesize(plan)

        assertEquals(first, second)
        assertEquals(walls, plan.walls)
        assertTrue(first.wallHeightMeters in 3.0f..3.5f)
        assertTrue(first.privacyPriority > 0.9f)
    }
}
