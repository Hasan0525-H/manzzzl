package com.manzl.app.analysis

import com.manzl.app.model.DoorEvidenceKind
import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DoorPresentationPolicyTest {

    @Test
    fun `geometry only gap is removed before user visible 3d`() {
        val measuredGap = door(Vec2(-1f, 0f), DoorEvidenceKind.MEASURED_GAP)
        val semanticDoor = door(Vec2(0f, 0f), DoorEvidenceKind.SEMANTIC_CONFIRMED)
        val userDoor = door(Vec2(1f, 0f), DoorEvidenceKind.USER_CONFIRMED)
        val plan = plan(listOf(measuredGap, semanticDoor, userDoor))

        val result = DoorPresentationPolicy.stripUnclassifiedGaps(plan)

        assertEquals(listOf(semanticDoor, userDoor), result.doors)
    }

    @Test
    fun `already confirmed door set is returned without copy`() {
        val plan = plan(
            listOf(
                door(Vec2(-1f, 0f), DoorEvidenceKind.SEMANTIC_CONFIRMED),
                door(Vec2(1f, 0f), DoorEvidenceKind.USER_CONFIRMED),
            )
        )

        assertSame(plan, DoorPresentationPolicy.stripUnclassifiedGaps(plan))
    }

    private fun door(center: Vec2, kind: DoorEvidenceKind) = DoorOpening(
        center = center,
        widthMeters = 1f,
        rotationDegrees = 0f,
        confidence = 0.9f,
        evidenceKind = kind,
    )

    private fun plan(doors: List<DoorOpening>) = FloorPlan(
        widthMeters = 8f,
        depthMeters = 8f,
        walls = emptyList(),
        doors = doors,
        analysisConfidence = 0.9f,
        sourceWidthPx = 1000,
        sourceHeightPx = 1000,
    )
}
