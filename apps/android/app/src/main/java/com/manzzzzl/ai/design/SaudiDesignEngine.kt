package com.manzzzl.ai.design

/**
 * Applies Saudi city-specific design rules before generating the final model.
 * Furniture is intentionally excluded; this layer handles architecture and materials only.
 */
object SaudiDesignEngine {
    fun apply(city: SaudiCityProfile): MaterialProfile {
        return MaterialProfile(
            exteriorStyle = city.name,
            recommendedMaterials = city.materials
        )
    }
}
