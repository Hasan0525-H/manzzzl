package com.manzzzl.ai.threeD

import com.manzzzl.ai.threeD.model.ThreeDModel

/**
 * Bridge between extracted 2D geometry and the 3D model layer.
 * Geometry generation stays independent from Saudi visual styling.
 */
object FloorPlanModelGenerator {
    fun generate(
        extractedGeometry: String? = null
    ): ThreeDModel {
        // Real wall/room/opening reconstruction will consume extracted geometry here.
        return ThreeDModel()
    }
}
