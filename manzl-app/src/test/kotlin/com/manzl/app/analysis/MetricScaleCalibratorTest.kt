package com.manzl.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricScaleCalibratorTest {

    @Test
    fun `western metre dimension remains metres`() {
        assertEquals(14.25f, MetricScaleCalibrator.parseDimensionMeters("14.25"))
    }

    @Test
    fun `arabic indic dimension is normalized`() {
        assertEquals(12.5f, MetricScaleCalibrator.parseDimensionMeters("١٢٫٥٠"))
    }

    @Test
    fun `CAD millimetres are converted to metres`() {
        assertEquals(14f, MetricScaleCalibrator.parseDimensionMeters("14000"))
    }

    @Test
    fun `implausible year is rejected`() {
        // 2026 is interpreted as millimetres = 2.026 m; it will not qualify as an overall dimension.
        assertEquals(2.026f, MetricScaleCalibrator.parseDimensionMeters("2026"))
    }

    @Test
    fun `non numeric text is rejected`() {
        assertNull(MetricScaleCalibrator.parseDimensionMeters("مجلس"))
    }

    @Test
    fun `orthogonal dimensions with matching scale produce high confidence`() {
        val result = MetricScaleCalibrator.resolveAxisEvidence(
            evidence = listOf(
                AxisDimensionEvidence(20f, DimensionAxis.HORIZONTAL, 0.88f),
                AxisDimensionEvidence(10f, DimensionAxis.VERTICAL, 0.86f),
            ),
            imageWidth = 2000,
            imageHeight = 1000,
        )

        assertTrue(result != null)
        assertEquals(20f, result!!.longSideMeters, 0.01f)
        assertTrue(result.confidence >= 0.80f)
        assertEquals("bundled_ocr_axis_pair", result.source)
    }

    @Test
    fun `vertical short side is converted to drawing long side instead of being mistaken for it`() {
        val result = MetricScaleCalibrator.resolveAxisEvidence(
            evidence = listOf(
                AxisDimensionEvidence(10f, DimensionAxis.VERTICAL, 0.82f),
            ),
            imageWidth = 2000,
            imageHeight = 1000,
        )

        assertTrue(result != null)
        assertEquals(20f, result!!.longSideMeters, 0.01f)
        assertEquals("bundled_ocr_vertical", result.source)
    }

    @Test
    fun `inconsistent orthogonal dimensions are not fused into a false consensus`() {
        val result = MetricScaleCalibrator.resolveAxisEvidence(
            evidence = listOf(
                AxisDimensionEvidence(20f, DimensionAxis.HORIZONTAL, 0.90f),
                AxisDimensionEvidence(15f, DimensionAxis.VERTICAL, 0.88f),
            ),
            imageWidth = 2000,
            imageHeight = 1000,
        )

        assertTrue(result != null)
        assertEquals(20f, result!!.longSideMeters, 0.01f)
        assertEquals("bundled_ocr_horizontal", result.source)
    }

    @Test
    fun `weak axis evidence is rejected`() {
        val result = MetricScaleCalibrator.resolveAxisEvidence(
            evidence = listOf(
                AxisDimensionEvidence(18f, DimensionAxis.HORIZONTAL, 0.40f),
            ),
            imageWidth = 1400,
            imageHeight = 900,
        )

        assertNull(result)
    }
}
