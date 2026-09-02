package com.manzzzl.ai.analysis

import com.manzzzl.ai.threeD.FloorPlanModelGenerator
import com.manzzzl.ai.threeD.model.ThreeDModel

/**
 * Pipeline bridge for converting a 2D plan into a model candidate.
 * Image understanding can be plugged in here without changing the 3D layer.
 */
object FloorPlanAnalysisPipeline {
    fun analyze(planImageReference: String?): ThreeDModel {
        val extractedGeometry = extractGeometry(planImageReference)
        return FloorPlanModelGenerator.generate(extractedGeometry)
    }

    private fun extractGeometry(planImageReference: String?): String? {
        // Reserved for OCR/CV wall-room-opening extraction engine.
        return planImageReference
    }
}
