package com.manzl.app.analysis

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityIssue
import com.manzl.app.model.GeometryFidelityIssueKind
import com.manzl.app.model.GeometryFidelityReport
import com.manzl.app.model.GeometryFidelityStatus
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndToEndGeometryReleaseGateTest {

    @Test
    fun `clean production-ready plan emits model-bound passing evidence without private source data`() {
        val report = EndToEndGeometryReleaseGate.evaluate(cleanPlan())

        assertTrue(report.passed)
        val json = report.toEvidenceJson(SAMPLE_ID, MODEL_SHA)
        assertTrue(json.contains("\"schema\": 2"))
        assertTrue(json.contains("\"modelSha256\": \"$MODEL_SHA\""))
        assertTrue(json.contains("\"geometryFidelityPass\": true"))
        assertTrue(json.contains("\"geometryQualityGatePassed\": true"))
        assertTrue(json.contains("\"reconstructionReadinessGatePassed\": true"))
        assertTrue(json.contains("\"endToEnd2dTo3dGeometryGatesPassed\": true"))
        assertTrue(json.contains("\"sourcePathsStored\": false"))
        assertTrue(json.contains("\"runtimeThresholdsDuplicated\": false"))
        assertFalse(json.contains("/storage/"))
    }

    @Test
    fun `aggregate fidelity pass cannot hide severe localized mismatch`() {
        val plan = cleanPlan().copy(
            geometryFidelity = passingFidelity().copy(
                issues = listOf(
                    GeometryFidelityIssue(
                        leftFraction = 0.10f,
                        topFraction = 0.10f,
                        rightFraction = 0.30f,
                        bottomFraction = 0.30f,
                        kind = GeometryFidelityIssueKind.MISSING_SOURCE,
                        severity = 0.80f,
                    )
                )
            )
        )

        val report = EndToEndGeometryReleaseGate.evaluate(plan)

        assertFalse(report.geometryQualityGatePassed)
        assertFalse(report.passed)
        assertTrue(report.toEvidenceJson("sample-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", MODEL_SHA)
            .contains("\"endToEnd2dTo3dGeometryGatesPassed\": false"))
    }

    @Test
    fun `room readiness failure blocks release even when wall fidelity passes`() {
        val plan = cleanPlan().copy(
            rooms = listOf(
                room("tiny", -0.8f, -0.8f, 0.8f, 0.8f),
            )
        )

        val report = EndToEndGeometryReleaseGate.evaluate(plan)

        assertTrue(report.geometryQualityGatePassed)
        assertFalse(report.reconstructionReadinessGatePassed)
        assertFalse(report.passed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `raw source-like sample id is rejected`() {
        EndToEndGeometryReleaseGate.evaluate(cleanPlan())
            .toEvidenceJson("riyadh-villa-client-17", MODEL_SHA)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non sha256 model identity is rejected`() {
        EndToEndGeometryReleaseGate.evaluate(cleanPlan())
            .toEvidenceJson(SAMPLE_ID, "not-a-model-digest")
    }

    private fun cleanPlan() = FloorPlan(
        widthMeters = 10f,
        depthMeters = 8f,
        walls = rectangleWalls(),
        rooms = listOf(room("main", -4f, -3f, 4f, 3f)),
        analysisConfidence = 0.95f,
        sourceWidthPx = 1200,
        sourceHeightPx = 960,
        geometryFidelity = passingFidelity(),
    )

    private fun passingFidelity() = GeometryFidelityReport(
        score = 0.91f,
        wallCoverage = 0.88f,
        wallPrecision = 0.93f,
        endpointSupport = 0.95f,
        status = GeometryFidelityStatus.PASS,
    )

    private fun rectangleWalls(): List<WallSegment> = listOf(
        WallSegment(Vec2(-4f, -3f), Vec2(4f, -3f), thicknessMeters = 0.18f, confidence = 0.95f),
        WallSegment(Vec2(4f, -3f), Vec2(4f, 3f), thicknessMeters = 0.18f, confidence = 0.95f),
        WallSegment(Vec2(4f, 3f), Vec2(-4f, 3f), thicknessMeters = 0.18f, confidence = 0.95f),
        WallSegment(Vec2(-4f, 3f), Vec2(-4f, -3f), thicknessMeters = 0.18f, confidence = 0.95f),
    )

    private fun room(
        id: String,
        minX: Float,
        minZ: Float,
        maxX: Float,
        maxZ: Float,
    ) = RoomRegion(
        id = id,
        polygon = listOf(
            Vec2(minX, minZ),
            Vec2(maxX, minZ),
            Vec2(maxX, maxZ),
            Vec2(minX, maxZ),
        ),
        confidence = 0.95f,
    )

    companion object {
        private const val SAMPLE_ID = "sample-33333333333333333333333333333333"
        private const val MODEL_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
