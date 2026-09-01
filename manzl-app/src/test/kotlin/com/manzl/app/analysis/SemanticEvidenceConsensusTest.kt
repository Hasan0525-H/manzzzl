package com.manzl.app.analysis

import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticEvidenceConsensusTest {

    @Test
    fun `agreeing local ai and classical cv strengthen one window observation`() {
        val cv = window(
            center = Vec2(1.00f, 0.02f),
            confidence = 0.72f,
            source = EvidenceSource.CLASSICAL_CV,
        )
        val ai = window(
            center = Vec2(1.08f, -0.03f),
            confidence = 0.81f,
            source = EvidenceSource.LOCAL_AI,
        )

        val result = SemanticEvidenceConsensus.combine(listOf(cv, ai))

        assertEquals(1, result.size)
        val combined = result.single()
        assertEquals(EvidenceSource.LOCAL_AI, combined.source)
        assertTrue(combined.confidence > ai.confidence)
        assertTrue(combined.center.x in 1.0f..1.08f)
        assertTrue(combined.widthMeters!! in 1.15f..1.25f)
    }

    @Test
    fun `two distinct concrete experts may corroborate even inside same source family`() {
        val hough = window(
            center = Vec2(0.02f, 0f),
            confidence = 0.72f,
            source = EvidenceSource.CLASSICAL_CV,
        ).copy(observerId = "opencv_hough_window")
        val symbol = window(
            center = Vec2(-0.03f, 0.02f),
            confidence = 0.76f,
            source = EvidenceSource.CLASSICAL_CV,
        ).copy(observerId = "double_line_symbol_window")

        val result = SemanticEvidenceConsensus.combine(listOf(hough, symbol))

        assertEquals(1, result.size)
        assertTrue(result.single().confidence > maxOf(hough.confidence, symbol.confidence))
        assertTrue(result.single().observerId.orEmpty().contains("opencv_hough_window"))
        assertTrue(result.single().observerId.orEmpty().contains("double_line_symbol_window"))
    }

    @Test
    fun `repeated hypotheses from one concrete expert count only once`() {
        val weaker = window(
            center = Vec2(0f, 0f),
            confidence = 0.68f,
            source = EvidenceSource.CLASSICAL_CV,
        ).copy(observerId = "opencv_stair_or_window_detector")
        val stronger = window(
            center = Vec2(0.03f, -0.02f),
            confidence = 0.83f,
            source = EvidenceSource.CLASSICAL_CV,
        ).copy(observerId = "opencv_stair_or_window_detector")

        val result = SemanticEvidenceConsensus.combine(listOf(weaker, stronger))

        assertEquals(1, result.size)
        assertEquals(stronger.confidence, result.single().confidence, 0.00001f)
        assertEquals(stronger.center, result.single().center)
        assertEquals(stronger.observerId, result.single().observerId)
    }

    @Test
    fun `spatially conflicting window observations remain separate`() {
        val left = window(Vec2(-1.2f, 0f), 0.82f, EvidenceSource.CLASSICAL_CV)
        val right = window(Vec2(1.2f, 0f), 0.84f, EvidenceSource.LOCAL_AI)

        val result = SemanticEvidenceConsensus.combine(listOf(left, right))

        assertEquals(2, result.size)
    }

    @Test
    fun `explicit user correction is never averaged away`() {
        val ai = window(Vec2(0.10f, 0f), 0.90f, EvidenceSource.LOCAL_AI)
        val user = SemanticEvidence(
            kind = SemanticKind.WINDOW,
            center = Vec2(0f, 0f),
            widthMeters = 1.40f,
            rotationDegrees = 0f,
            confidence = 0.60f,
            source = EvidenceSource.USER_CORRECTION,
        )

        val result = SemanticEvidenceConsensus.combine(listOf(ai, user))

        assertEquals(1, result.size)
        assertEquals(user, result.single())
    }

    @Test
    fun `room polygons are preserved instead of geometrically averaged`() {
        val roomA = SemanticEvidence(
            kind = SemanticKind.ROOM,
            center = Vec2(0f, 0f),
            polygon = listOf(Vec2(-1f, -1f), Vec2(1f, -1f), Vec2(1f, 1f), Vec2(-1f, 1f)),
            label = "صالة",
            confidence = 0.86f,
            source = EvidenceSource.LOCAL_AI,
        )
        val roomB = roomA.copy(
            center = Vec2(0.05f, 0.02f),
            label = null,
            confidence = 0.74f,
            source = EvidenceSource.CLASSICAL_CV,
        )

        val result = SemanticEvidenceConsensus.combine(listOf(roomA, roomB))

        assertEquals(2, result.size)
        assertTrue(result.contains(roomA))
        assertTrue(result.contains(roomB))
    }

    private fun window(
        center: Vec2,
        confidence: Float,
        source: EvidenceSource,
    ): SemanticEvidence = SemanticEvidence(
        kind = SemanticKind.WINDOW,
        center = center,
        widthMeters = if (source == EvidenceSource.LOCAL_AI) 1.24f else 1.18f,
        rotationDegrees = if (source == EvidenceSource.LOCAL_AI) 2f else 0f,
        confidence = confidence,
        source = source,
    )
}
