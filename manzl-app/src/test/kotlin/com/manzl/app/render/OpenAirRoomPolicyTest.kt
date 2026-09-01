package com.manzl.app.render

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAirRoomPolicyTest {

    @Test
    fun `trusted courtyard keeps floor but receives no ceiling`() {
        val courtyard = room(label = "فناء", confidence = 0.95f)
        val plan = plan(courtyard)

        val mesh = HouseMeshBuilder.build(plan)

        assertTrue("courtyard should retain a walkable floor", mesh.floorVertices.isNotEmpty())
        assertTrue("trusted courtyard was incorrectly roofed", mesh.ceilingVertices.isEmpty())
        assertTrue(OpenAirRoomPolicy.shouldRemainOpenToSky(courtyard))
    }

    @Test
    fun `ordinary living room still receives a ceiling`() {
        val living = room(label = "صالة", confidence = 0.95f)

        val mesh = HouseMeshBuilder.build(plan(living))

        assertFalse("interior room unexpectedly lost its ceiling", mesh.ceilingVertices.isEmpty())
        assertFalse(OpenAirRoomPolicy.shouldRemainOpenToSky(living))
    }

    @Test
    fun `weak courtyard label fails closed and remains covered`() {
        // Keep this above the mesh ceiling threshold while below the open-air semantic threshold.
        // That isolates the policy under test: uncertain OCR must not punch a roof opening.
        val weak = room(label = "فناء", confidence = 0.70f)

        val mesh = HouseMeshBuilder.build(plan(weak))

        assertFalse("low-confidence OCR punched an open-air void", mesh.ceilingVertices.isEmpty())
        assertFalse(OpenAirRoomPolicy.shouldRemainOpenToSky(weak))
    }

    private fun room(label: String, confidence: Float): RoomRegion = RoomRegion(
        id = label,
        polygon = listOf(
            Vec2(-2f, -2f),
            Vec2(2f, -2f),
            Vec2(2f, 2f),
            Vec2(-2f, 2f),
        ),
        label = label,
        confidence = confidence,
    )

    private fun plan(room: RoomRegion): FloorPlan = FloorPlan(
        widthMeters = 6f,
        depthMeters = 6f,
        walls = emptyList(),
        rooms = listOf(room),
        analysisConfidence = 1f,
        sourceWidthPx = 1000,
        sourceHeightPx = 1000,
    )
}
