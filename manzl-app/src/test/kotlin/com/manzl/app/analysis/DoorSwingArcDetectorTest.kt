package com.manzl.app.analysis

import com.manzl.app.model.DoorHingeSide
import com.manzl.app.model.DoorOpening
import com.manzl.app.model.DoorSwingSide
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class DoorSwingArcDetectorTest {

    @Test
    fun `quarter circle identifies start hinge swinging to positive normal`() {
        val plan = planWithSingleHorizontalDoor()
        val ink = mutableSetOf<Pair<Int, Int>>()
        drawArc(
            ink = ink,
            hingeX = 450,
            hingeY = 500,
            radiusPx = 93,
            startDegrees = 20,
            endDegrees = 90,
        )

        val enriched = DoorSwingArcDetector.enrichWithSampler(
            plan = plan,
            widthPx = 1000,
            heightPx = 1000,
        ) { x, y -> if ((x to y) in ink) 1f else 0f }

        val door = enriched.single()
        assertEquals(DoorHingeSide.AXIS_START, door.hingeSide)
        assertEquals(DoorSwingSide.POSITIVE_NORMAL, door.swingSide)
        assertTrue(door.swingConfidence >= 0.66f)
    }

    @Test
    fun `mirrored quarter circle identifies end hinge and negative normal`() {
        val plan = planWithSingleHorizontalDoor()
        val ink = mutableSetOf<Pair<Int, Int>>()
        // End hinge is x=550. Closed leaf points left; negative normal is upward on the raster.
        // The corresponding world directions run from 180 degrees toward 270 degrees.
        for (degrees in 200..268 step 2) {
            val radians = degrees * PI / 180.0
            val x = 550 + (93 * cos(radians)).roundToInt()
            val y = 500 + (93 * sin(radians)).roundToInt()
            stamp(ink, x, y)
        }

        val enriched = DoorSwingArcDetector.enrichWithSampler(
            plan = plan,
            widthPx = 1000,
            heightPx = 1000,
        ) { x, y -> if ((x to y) in ink) 1f else 0f }

        val door = enriched.single()
        assertEquals(DoorHingeSide.AXIS_END, door.hingeSide)
        assertEquals(DoorSwingSide.NEGATIVE_NORMAL, door.swingSide)
    }

    @Test
    fun `ambiguous symmetric evidence fails closed`() {
        val plan = planWithSingleHorizontalDoor()
        val ink = mutableSetOf<Pair<Int, Int>>()
        drawArc(ink, hingeX = 450, hingeY = 500, radiusPx = 93, startDegrees = 20, endDegrees = 90)
        drawArc(ink, hingeX = 450, hingeY = 500, radiusPx = 93, startDegrees = -90, endDegrees = -20)

        val enriched = DoorSwingArcDetector.enrichWithSampler(
            plan = plan,
            widthPx = 1000,
            heightPx = 1000,
        ) { x, y -> if ((x to y) in ink) 1f else 0f }

        val door = enriched.single()
        assertEquals(DoorHingeSide.UNKNOWN, door.hingeSide)
        assertEquals(DoorSwingSide.UNKNOWN, door.swingSide)
        assertEquals(0f, door.swingConfidence, 0.0001f)
    }

    @Test
    fun `blank raster does not invent hinge`() {
        val plan = planWithSingleHorizontalDoor()

        val enriched = DoorSwingArcDetector.enrichWithSampler(
            plan = plan,
            widthPx = 1000,
            heightPx = 1000,
        ) { _, _ -> 0f }

        assertEquals(DoorHingeSide.UNKNOWN, enriched.single().hingeSide)
    }

    private fun planWithSingleHorizontalDoor(): FloorPlan = FloorPlan(
        widthMeters = 10f,
        depthMeters = 10f,
        walls = emptyList(),
        doors = listOf(
            DoorOpening(
                center = Vec2(0f, 0f),
                widthMeters = 1f,
                rotationDegrees = 0f,
                confidence = 0.90f,
            )
        ),
        analysisConfidence = 0.9f,
        sourceWidthPx = 1000,
        sourceHeightPx = 1000,
    )

    private fun drawArc(
        ink: MutableSet<Pair<Int, Int>>,
        hingeX: Int,
        hingeY: Int,
        radiusPx: Int,
        startDegrees: Int,
        endDegrees: Int,
    ) {
        val step = if (endDegrees >= startDegrees) 2 else -2
        var degrees = startDegrees
        while ((step > 0 && degrees <= endDegrees) || (step < 0 && degrees >= endDegrees)) {
            val radians = degrees * PI / 180.0
            val x = hingeX + (radiusPx * cos(radians)).roundToInt()
            val y = hingeY + (radiusPx * sin(radians)).roundToInt()
            stamp(ink, x, y)
            degrees += step
        }
    }

    private fun stamp(ink: MutableSet<Pair<Int, Int>>, x: Int, y: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                if (dx * dx + dy * dy <= 4) ink += (x + dx) to (y + dy)
            }
        }
    }
}
