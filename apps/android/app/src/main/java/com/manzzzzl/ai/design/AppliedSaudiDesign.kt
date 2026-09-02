package com.manzzzl.ai.design

import com.manzzzl.ai.threeD.model.ThreeDModel

/**
 * Applies Saudi city design context as a separate visual layer.
 * Geometry remains independent from decoration and materials.
 */
data class AppliedSaudiDesign(
    val city: String,
    val materials: MaterialProfile,
    val architecture: SaudiArchitectureContext
)

fun applySaudiDesign(
    model: ThreeDModel,
    cityProfile: SaudiCityProfile
): Pair<ThreeDModel, AppliedSaudiDesign> {
    val design = AppliedSaudiDesign(
        city = cityProfile.city,
        materials = MaterialProfile(),
        architecture = SaudiArchitectureContext()
    )

    return Pair(model, design)
}
