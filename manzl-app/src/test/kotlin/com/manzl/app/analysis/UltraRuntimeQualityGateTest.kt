package com.manzl.app.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraRuntimeQualityGateTest {

    @Test
    fun `missing student blocks ultra reconstruction even when MobileSAM is present`() {
        val decision = UltraRuntimeQualityGate.evaluate(
            UltraModelAvailability(
                onnxRuntimeReady = true,
                presentAssets = setOf(
                    UltraModelCatalog.MOBILE_SAM_ENCODER,
                    UltraModelCatalog.MOBILE_SAM_DECODER,
                ),
            )
        )

        assertFalse(decision.ready)
        assertTrue(UltraModelCatalog.MANZL_RECONSTRUCTION_STUDENT in decision.missingAssets)
        assertTrue(decision.messageArabic.orEmpty().contains("Manzl Reconstruction Student"))
    }

    @Test
    fun `all required local models allow ultra reconstruction`() {
        val decision = UltraRuntimeQualityGate.evaluate(
            UltraModelAvailability(
                onnxRuntimeReady = true,
                presentAssets = UltraModelCatalog.requiredForUltraRuntime.toSet(),
            )
        )

        assertTrue(decision.ready)
        assertTrue(decision.missingAssets.isEmpty())
        assertTrue(decision.messageArabic == null)
    }

    @Test
    fun `missing ONNX runtime blocks without cloud fallback`() {
        val decision = UltraRuntimeQualityGate.evaluate(
            UltraModelAvailability(
                onnxRuntimeReady = false,
                presentAssets = UltraModelCatalog.requiredForUltraRuntime.toSet(),
            )
        )

        assertFalse(decision.ready)
        assertTrue(decision.messageArabic.orEmpty().contains("ONNX Runtime"))
    }
}
