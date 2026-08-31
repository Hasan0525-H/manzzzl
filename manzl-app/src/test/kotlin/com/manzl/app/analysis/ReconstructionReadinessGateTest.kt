package com.manzl.app.analysis

import com.manzl.app.model.DoorEvidenceKind
import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import com.manzl.app.model.WindowOpening
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconstructionReadinessGateTest {

    @Test
    fun `closed room with no unresolved openings is ready`() {
        val report = ReconstructionReadinessGate.evaluate(
            plan(
                walls = rectangleWalls(),
                rooms = listOf(largeRoom()),
            )
        )

        assertTrue(report.ready)
        assertTrue(report.unresolvedOpenings.isEmpty())
        assertTrue(report.trustedRoomCoverage >= 0.32f)
    }

    @Test
    fun `strong measured gap without semantic class blocks 3d`() {
        val report = ReconstructionReadinessGate.evaluate(
            plan(
                walls = gapWalls(),
                rooms = listOf(largeRoom()),
            )
        )

        assertFalse(report.ready)
        assertTrue(report.unresolvedOpenings.isNotEmpty())
    }

    @Test
    fun `geometry only door label does not resolve ambiguous gap`() {
        val report = ReconstructionReadinessGate.evaluate(
            plan(
                walls = gapWalls(),
                doors = listOf(
                    DoorOpening(
                        center = Vec2(0f, -3f),
                        widthMeters = 1.0f,
                        rotationDegrees = 0f,
                        confidence = 0.95f,
                        evidenceKind = DoorEvidenceKind.MEASURED_GAP,
                    )
                ),
                rooms = listOf(largeRoom()),
            )
        )

        assertFalse(report.ready)
        assertTrue(report.unresolvedOpenings.isNotEmpty())
    }

    @Test
    fun `semantic confirmed door resolves measured gap`() {
        val report = ReconstructionReadinessGate.evaluate(
            plan(
                walls = gapWalls(),
                doors = listOf(
                    DoorOpening(
                        center = Vec2(0f, -3f),
                        widthMeters = 1.0f,
                        rotationDegrees = 0f,
                        confidence = 0.92f,
                        evidenceKind = DoorEvidenceKind.SEMANTIC_CONFIRMED,
                    )
                ),
                rooms = listOf(largeRoom()),
            )
        )

        assertTrue(report.ready)
        assertTrue(report.unresolvedOpenings.isEmpty())
    }

    @Test
    fun `confirmed window resolves measured gap`() {
        val report = ReconstructionReadinessGate.evaluate(
            plan(
                walls = gapWalls(),
                windows = listOf(
                    WindowOpening(
                        center = Vec2(0f, -3f),
                        widthMeters = 1.0f,
                        rotationDegrees = 0f,
                        confidence = 0.94f,
                    )
                ),
                rooms = listOf(largeRoom()),
            )
        )

        assertTrue(report.ready)
        assertTrue(report.unresolvedOpenings.isEmpty())
    }

    @Test
    fun `sparse room topology blocks fake rectangular floor`() {
        val smallRoom = RoomRegion(
            id = "small",
            polygon = listOf(
                Vec2(-1f, -1f),
                Vec2(1f, -1f),
                Vec2(1f, 1f),
                Vec2(-1f, 1f),
            ),
            confidence = 0.96f,
        )
        val report = ReconstructionReadinessGate.evaluate(
            plan(
                walls = rectangleWalls(),
                rooms = listOf(smallRoom),
            )
        )

        assertFalse(report.ready)
        assertTrue(report.trustedRoomCoverage < 0.32f)
    }

    private fun rectangleWalls(): List<WallSegment> = listOf(
        WallSegment(Vec2(-4f, -3f), Vec2(4f, -3f), thicknessMeters = 0.18f, confidence = 0.95f),
        WallSegment(Vec2(4f, -3f), Vec2(4f, 3f), thicknessMeters = 0.18f, confidence = 0.95f),
        WallSegment(Vec2(4f, 3f), Vec2(-4f, 3f), thicknessMeters = 0.18f, confidence = 0.95f),
        WallSegment(Vec2(-4f, 3f), Vec2(-4f, -3f), thicknessMeters = 0.18f, confidence = 0.95f),
    )

    private fun gapWalls(): List<WallSegment> = listOf(
        WallSegment(Vec2(-4f, -3f), Vec2(-0.5f, -3f), thicknessMeters = 0.18f, confidence = 0.95f),
        WallSegment(Vec2(0.5f, -3f), Vec2(4f, -3f), thicknessMeters = 0.18f, confidence = 0.95f),
        WallSegment(Vec2(4f, -3f), Vec2(4f, 3f), thicknessMeters = 0.18f, confidence = 0.95f),
        WallSegment(Vec2(4f, 3f), Vec2(-4f, 3f), thicknessMeters = 0.18f, confidence = 0.95f),
        WallSegment(Vec2(-4f, 3f), Vec2(-4f, -3f), thicknessMeters = 0.18f, confidence = 0.95f),
    )

    private fun largeRoom() = RoomRegion(
        id = "main",
        polygon = listOf(
            Vec2(-4f, -3f),
            Vec2(4f, -3f),
            Vec2(4f, 3f),
            Vec2(-4f, 3f),
        ),
        confidence = 0.95f,
    )

    private fun plan(
        walls: List<WallSegment>,
        doors: List<DoorOpening> = emptyList(),
        windows: List<WindowOpening> = emptyList(),
        rooms: List<RoomRegion>,
    ) = FloorPlan(
        widthMeters = 10f,
        depthMeters = 8f,
        walls = walls,
        doors = doors,
        windows = windows,
        rooms = rooms,
        analysisConfidence = 0.95f,
        sourceWidthPx = 1200,
        sourceHeightPx = 960,
    )
}
