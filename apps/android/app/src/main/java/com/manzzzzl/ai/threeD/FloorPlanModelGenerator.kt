package com.manzzzl.ai.threeD

import com.manzzzl.ai.threeD.model.ThreeDModel

/**
 * First bridge between analyzed 2D plans and the 3D model layer.
 * Geometry generation stays independent from Saudi visual styling.
 */
object FloorPlanModelGenerator {
    fun generate(
        floorPlanData: String? = null
    ): ThreeDModel {
        // Placeholder until the image analysis engine provides geometry.
        // Keeps the pipeline ready for the real 2D parser.
        return ThreeDModel()
    }
}
