package com.manzzzl.ai.design

/**
 * Applies Saudi exterior design decisions to a generated design session.
 * Geometry remains independent from visual styling.
 */
object SaudiDesignSessionApplier {
    fun apply(
        session: DesignGenerationSession,
        profile: SaudiExteriorProfile
    ): AppliedSaudiDesign {
        return AppliedSaudiDesign(
            city = session.questionnaire.city ?: "غير محددة",
            materials = profile.materials,
            climateStrategy = profile.climateStrategy,
            facadeStyle = profile.facadeStyle
        )
    }
}

data class AppliedSaudiDesign(
    val city: String,
    val materials: List<String>,
    val climateStrategy: String,
    val facadeStyle: String
)
