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
            plan(walls = rectangleWalls(), rooms = listOf(largeRoom()))
        )
        assertTrue(report.ready)
        assertTrue(report.unresolvedOpenings.isEmpty())
        assertTrue(report.unsupportedVerticalVoids.isEmpty())
        assertTrue(report.unsupportedRoomBoundaries.isEmpty())
        assertTrue(report.trustedRoomCoverage >= 0.90f)
    }

    @Test
    fun `strong measured gap without semantic class blocks 3d`() {
        val report = ReconstructionReadinessGate.evaluate(
            plan(walls = gapWalls(), rooms = listOf(largeRoom()))
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
        assertTrue(report.unsupportedRoomBoundaries.isEmpty())
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
        assertTrue(report.unsupportedRoomBoundaries.isEmpty())
    }

    @Test
    fun `sparse room topology blocks fake rectangular floor`() {
        val smallRoom = room("small", -1f, -1f, 1f, 1f)
        val report = ReconstructionReadinessGate.evaluate(
            plan(walls = rectangleWalls(), rooms = listOf(smallRoom))
        )
        assertFalse(report.ready)
        assertTrue(report.trustedRoomCoverage < 0.15f)
    }

    @Test
    fun `forty percent room reconstruction is still blocked`() {
        val mediumRoom = room("medium", -2.5f, -2f, 2.5f, 2f)
        val report = ReconstructionReadinessGate.evaluate(
            plan(walls = rectangleWalls(), rooms = listOf(mediumRoom))
        )
        assertFalse(report.ready)
        assertTrue(report.trustedRoomCoverage in 0.35f..0.45f)
    }

    @Test
    fun `high coverage room with an invented boundary is still blocked`() {
        val walls = rectangleWalls().filterIndexed { index, _ -> index != 1 }
        val report = ReconstructionReadinessGate.evaluate(
            plan(walls = walls, rooms = listOf(largeRoom()))
        )

        assertFalse(report.ready)
        assertTrue(report.trustedRoomCoverage >= 0.90f)
        assertTrue(report.unsupportedRoomBoundaries.any { it.roomId == "main" })
    }

    @Test
    fun `coverage ignores conservative empty padding outside measured wall envelope`() {
        val report = ReconstructionReadinessGate.evaluate(
            FloorPlan(
                widthMeters = 20f,
                depthMeters = 18f,
                walls = rectangleWalls(),
                rooms = listOf(largeRoom()),
                analysisConfidence = 0.95f,
                sourceWidthPx = 1600,
                sourceHeightPx = 1200,
            )
        )
        assertTrue(report.ready)
        assertTrue(report.trustedRoomCoverage >= 0.90f)
    }

    @Test
    fun `strictly nested shaft is supported only when its boundary is measured`() {
        val shaft = room("shaft", -1f, -1f, 1f, 1f, label = "shaft")
        val report = ReconstructionReadinessGate.evaluate(
            plan(
                walls = rectangleWalls() + roomBoundaryWalls(shaft),
                rooms = listOf(largeRoom(), shaft),
            )
        )
        assertTrue(report.unsupportedVerticalVoids.isEmpty())
        assertTrue(report.unsupportedRoomBoundaries.isEmpty())
        assertTrue(report.ready)
    }

    @Test
    fun `partially overlapping shaft remains blocked`() {
        val shaft = room("shaft", 3.4f, -1f, 4.6f, 1f, label = "shaft")
        val report = ReconstructionReadinessGate.evaluate(
            plan(
                walls = rectangleWalls() + roomBoundaryWalls(shaft),
                rooms = listOf(largeRoom(), shaft),
            )
        )
        assertFalse(report.ready)
        assertTrue(report.unsupportedVerticalVoids.any { it.id == "shaft" })
    }

    @Test
    fun `independent closed shaft face is allowed when every face boundary is measured`() {
        val left = room("left", -4f, -3f, -1f, 3f)
        val right = room("right", 1f, -3f, 4f, 3f)
        val shaft = room("shaft", -0.7f, -1f, 0.7f, 1f, label = "shaft")
        val walls = rectangleWalls() +
            roomBoundaryWalls(left) +
            roomBoundaryWalls(right) +
            roomBoundaryWalls(shaft)
        val report = ReconstructionReadinessGate.evaluate(
            plan(walls = walls, rooms = listOf(left, right, shaft))
        )
        assertTrue(report.unsupportedVerticalVoids.isEmpty())
        assertTrue(report.unsupportedRoomBoundaries.isEmpty())
        assertTrue(report.trustedRoomCoverage >= 0.68f)
        assertTrue(report.ready)
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

    private fun roomBoundaryWalls(room: RoomRegion): List<WallSegment> = room.polygon.indices.map { index ->
        WallSegment(
            start = room.polygon[index],
            end = room.polygon[(index + 1) % room.polygon.size],
            thicknessMeters = 0.18f,
            confidence = 0.95f,
        )
    }

    private fun largeRoom() = room("main", -4f, -3f, 4f, 3f)

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
