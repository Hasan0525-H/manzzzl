package com.manzl.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
