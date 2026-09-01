package com.manzl.app.analysis

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityReport
import com.manzl.app.model.GeometryFidelityStatus
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryCorrectionEngineTest {

    @Test
    fun `explicit endpoint correction changes only requested wall and invalidates derived semantics`() {
        val source = plan().copy(
            doors = listOf(DoorOpening(Vec2(0f, -3f), 0.9f, 0f, 0.8f)),
            rooms = listOf(RoomRegion("r", square(-2f, -2f, 2f, 2f), confidence = 0.8f)),
            geometryFidelity = GeometryFidelityReport(
                score = 0.4f,
                wallCoverage = 0.4f,
                wallPrecision = 0.5f,
                endpointSupport = 0.5f,
                status = GeometryFidelityStatus.BLOCKED,
            ),
        )

        val result = GeometryCorrectionEngine.apply(
            source,
            listOf(
                GeometryCorrection.MoveEndpoint(
                    wallIndex = 0,
                    endpoint = WallEndpoint.END,
                    target = Vec2(3.7f, -3f),
                )
            ),
        )

        assertEquals(1, result.appliedCount)
        assertEquals(0, result.rejectedCount)
        assertEquals(Vec2(3.7f, -3f), result.plan.walls[0].end)
        assertEquals(1f, result.plan.walls[0].confidence)
        assertTrue(result.plan.doors.isEmpty())
        assertTrue(result.plan.rooms.isEmpty())
        assertEquals(GeometryFidelityStatus.UNKNOWN, result.plan.geometryFidelity.status)
    }

    @Test
    fun `unsafe or degenerate edits are rejected fail closed`() {
        val source = plan()
        val result = GeometryCorrectionEngine.apply(
            source,
            listOf(
                GeometryCorrection.MoveEndpoint(0, WallEndpoint.END, Vec2(-3.95f, -3f)),
                GeometryCorrection.TranslateWall(1, 9f, 0f),
                GeometryCorrection.SetThickness(2, 1.2f),
                GeometryCorrection.AddWall(Vec2(0f, 0f), Vec2(0.04f, 0f), 0.18f),
            ),
        )

        assertEquals(0, result.appliedCount)
        assertEquals(4, result.rejectedCount)
        assertEquals(source.walls, result.plan.walls)
    }

    @Test
    fun `user can add a diagonal wall with measured thickness`() {
        val source = plan()
        val result = GeometryCorrectionEngine.apply(
            source,
            listOf(
                GeometryCorrection.AddWall(
                    start = Vec2(-1.5f, -1f),
                    end = Vec2(2f, 1.8f),
                    thicknessMeters = 0.22f,
                )
            ),
        )

        assertEquals(1, result.appliedCount)
        assertEquals(source.walls.size + 1, result.plan.walls.size)
        val added = result.plan.walls.last()
        assertEquals(0.22f, added.thicknessMeters)
        assertEquals(1f, added.confidence)
        assertEquals(Vec2(-1.5f, -1f), added.start)
        assertEquals(Vec2(2f, 1.8f), added.end)
    }

    private fun plan() = FloorPlan(
        widthMeters = 10f,
        depthMeters = 8f,
        walls = listOf(
            wall(-4f, -3f, 4f, -3f),
            wall(4f, -3f, 4f, 3f),
            wall(4f, 3f, -4f, 3f),
            wall(-4f, 3f, -4f, -3f),
            wall(0f, -3f, 0f, 3f),
        ),
        analysisConfidence = 0.7f,
        sourceWidthPx = 1800,
        sourceHeightPx = 1400,
    )

    private fun wall(x0: Float, z0: Float, x1: Float, z1: Float) = WallSegment(
        start = Vec2(x0, z0),
        end = Vec2(x1, z1),
        thicknessMeters = 0.18f,
        confidence = 0.8f,
    )

    private fun square(x0: Float, z0: Float, x1: Float, z1: Float) = listOf(
        Vec2(x0, z0), Vec2(x1, z0), Vec2(x1, z1), Vec2(x0, z1)
    )
}
