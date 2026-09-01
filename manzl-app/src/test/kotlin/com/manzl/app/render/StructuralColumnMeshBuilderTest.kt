package com.manzl.app.render

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.StructuralColumn
import com.manzl.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuralColumnMeshBuilderTest {

    @Test
    fun `verified rotated column becomes one closed six face solid`() {
        val plan = FloorPlan(
            widthMeters = 8f,
            depthMeters = 8f,
            walls = emptyList(),
            columns = listOf(
                StructuralColumn(
                    center = Vec2(0.6f, -0.4f),
                    widthMeters = 0.45f,
                    depthMeters = 0.60f,
                    rotationDegrees = 33f,
                    heightMeters = 3.1f,
                    confidence = 0.95f,
                )
            ),
            analysisConfidence = 1f,
            sourceWidthPx = 1000,
            sourceHeightPx = 1000,
        )

        val mesh = StructuralColumnMeshBuilder.build(plan)

        assertEquals(6 * 4 * 6, mesh.wallVertices.size)
        assertEquals(6 * 6, mesh.wallIndices.size)
        assertTrue(mesh.wallVertices.all { it.isFinite() })
        assertTrue(mesh.floorVertices.isEmpty())
        assertTrue(mesh.trimVertices.isEmpty())
    }

    @Test
    fun `unverified column proposal never reaches visible mesh`() {
        val plan = FloorPlan(
            widthMeters = 8f,
            depthMeters = 8f,
            walls = emptyList(),
            columns = listOf(
                StructuralColumn(
                    center = Vec2(0f, 0f),
                    widthMeters = 0.5f,
                    depthMeters = 0.5f,
                    confidence = 0.52f,
                )
            ),
            analysisConfidence = 1f,
            sourceWidthPx = 1000,
            sourceHeightPx = 1000,
        )

        val mesh = StructuralColumnMeshBuilder.build(plan)

        assertTrue(mesh.wallVertices.isEmpty())
        assertTrue(mesh.wallIndices.isEmpty())
    }

    @Test
    fun `design height override controls column top consistently with walls`() {
        val plan = FloorPlan(
            widthMeters = 8f,
            depthMeters = 8f,
            walls = emptyList(),
            columns = listOf(
                StructuralColumn(
                    center = Vec2(0f, 0f),
                    widthMeters = 0.4f,
                    depthMeters = 0.4f,
                    heightMeters = 2.4f,
                    confidence = 0.94f,
                )
            ),
            analysisConfidence = 1f,
            sourceWidthPx = 1000,
            sourceHeightPx = 1000,
        )

        val mesh = StructuralColumnMeshBuilder.build(plan, heightOverrideMeters = 3.35f)
        val yValues = mesh.wallVertices.toList().chunked(6).map { it[1] }

        assertEquals(3.35f, yValues.maxOrNull() ?: 0f, 0.001f)
        assertEquals(0f, yValues.minOrNull() ?: -1f, 0.001f)
    }
}
