package com.manzzzl.ai.design

/**
 * Builds a complete design session before opening the 3D viewer.
 * Keeps geometry generation separate from Saudi visual styling.
 */
object DesignSessionBuilder {

    fun build(
        questionnaire: DesignQuestionnaire,
        model: com.manzzzl.ai.threeD.model.ThreeDModel,
        cityProfile: SaudiCityProfile
    ): DesignGenerationSession? {
        if (!DesignGenerationValidator.validate(questionnaire)) {
            return null
        }

        val (_, appliedDesign) = applySaudiDesign(model, cityProfile)

        return DesignGenerationSession(
            questionnaire = questionnaire,
            appliedDesign = appliedDesign,
            isReady = true
        )
    }
}
