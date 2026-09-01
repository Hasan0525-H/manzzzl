package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentSemanticEvidenceProjectorTest {

    @Test
    fun `door class only confirms an existing measured wall gap`() {
        val plan = planWithHorizontalGap()
        val transform = PlanRasterTransform.forImage(plan, 1000, 800)
        val centerImage = transform.planToImage(Vec2(0f, 0f))
        val component = StudentSemanticComponentDecoder.Component(
            classId = StudentSemanticComponentDecoder.DOOR_CLASS_ID,
            centerX = centerImage.first,
            centerY = centerImage.second,
            majorSpanPx = 50f,
            minorSpanPx = 20f,
            rotationDegrees = 0f,
            confidence = 0.92f,
            pixelCount = 70,
            touchesModelEdge = false,
        )

        val evidence = StudentSemanticEvidenceProjector.project(
            components = listOf(component),
            seed = plan,
            sourceTransform = transform,
            modelToSource = { x, y -> x to y },
            detailPass = false,
        )

        assertEquals(1, evidence.size)
        assertEquals(SemanticKind.DOOR, evidence.single().kind)
        assertEquals(1.0f, evidence.single().widthMeters ?: 0f, 0.05f)
        assertEquals(0f, evidence.single().center.x, 0.05f)
    }

    @Test
    fun `door class over continuous wall cannot invent an opening`() {
        val plan = planWithHorizontalGap().copy(
            walls = listOf(
                WallSegment(Vec2(-4f, 0f), Vec2(4f, 0f), confidence = 0.95f),
            )
        )
        val transform = PlanRasterTransform.forImage(plan, 1000, 800)
        val centerImage = transform.planToImage(Vec2(0f, 0f))
        val component = StudentSemanticComponentDecoder.Component(
            classId = StudentSemanticComponentDecoder.DOOR_CLASS_ID,
            centerX = centerImage.first,
            centerY = centerImage.second,
            majorSpanPx = 50f,
            minorSpanPx = 20f,
            rotationDegrees = 0f,
            confidence = 0.99f,
            pixelCount = 90,
            touchesModelEdge = false,
        )

        val evidence = StudentSemanticEvidenceProjector.project(
            components = listOf(component),
            seed = plan,
            sourceTransform = transform,
            modelToSource = { x, y -> x to y },
            detailPass = false,
        )

        assertTrue(evidence.isEmpty())
    }

    @Test
    fun `courtyard class labels an existing measured room without changing its polygon`() {
        val room = RoomRegion(
            id = "court",
            polygon = listOf(
                Vec2(-1.5f, -1.2f),
                Vec2(1.5f, -1.2f),
                Vec2(1.5f, 1.2f),
                Vec2(-1.5f, 1.2f),
            ),
            confidence = 0.91f,
        )
        val plan = planWithHorizontalGap().copy(rooms = listOf(room))
        val transform = PlanRasterTransform.forImage(plan, 1000, 800)
        val centerImage = transform.planToImage(Vec2(0f, 0f))
        val component = StudentSemanticComponentDecoder.Component(
            classId = StudentSemanticComponentDecoder.COURTYARD_CLASS_ID,
            centerX = centerImage.first,
            centerY = centerImage.second,
            majorSpanPx = 160f,
            minorSpanPx = 120f,
            rotationDegrees = 0f,
            confidence = 0.93f,
            pixelCount = 500,
            touchesModelEdge = false,
        )

        val evidence = StudentSemanticEvidenceProjector.project(
            components = listOf(component),
            seed = plan,
            sourceTransform = transform,
            modelToSource = { x, y -> x to y },
            detailPass = false,
        )

        assertEquals(1, evidence.size)
        assertEquals(SemanticKind.ROOM, evidence.single().kind)
        assertEquals("courtyard", evidence.single().label)
        assertEquals(room.polygon, evidence.single().polygon)
    }

    @Test
    fun `courtyard class outside measured rooms cannot invent a room`() {
        val plan = planWithHorizontalGap().copy(
            rooms = listOf(
                RoomRegion(
                    id = "corner-room",
                    polygon = listOf(
                        Vec2(-4f, -3f),
                        Vec2(-2f, -3f),
                        Vec2(-2f, -1f),
                        Vec2(-4f, -1f),
                    ),
                    confidence = 0.94f,
                )
            )
        )
        val transform = PlanRasterTransform.forImage(plan, 1000, 800)
        val centerImage = transform.planToImage(Vec2(2f, 2f))
        val component = StudentSemanticComponentDecoder.Component(
            classId = StudentSemanticComponentDecoder.COURTYARD_CLASS_ID,
            centerX = centerImage.first,
            centerY = centerImage.second,
            majorSpanPx = 100f,
            minorSpanPx = 80f,
            rotationDegrees = 0f,
            confidence = 0.97f,
            pixelCount = 300,
            touchesModelEdge = false,
        )

        val evidence = StudentSemanticEvidenceProjector.project(
            components = listOf(component),
            seed = plan,
            sourceTransform = transform,
            modelToSource = { x, y -> x to y },
            detailPass = false,
        )

        assertTrue(evidence.isEmpty())
    }

    private fun planWithHorizontalGap() = FloorPlan(
        widthMeters = 10f,
        depthMeters = 8f,
        walls = listOf(
            WallSegment(Vec2(-4f, 0f), Vec2(-0.5f, 0f), confidence = 0.95f),
            WallSegment(Vec2(0.5f, 0f), Vec2(4f, 0f), confidence = 0.95f),
        ),
        analysisConfidence = 0.95f,
        sourceWidthPx = 1000,
        sourceHeightPx = 800,
    )
}
