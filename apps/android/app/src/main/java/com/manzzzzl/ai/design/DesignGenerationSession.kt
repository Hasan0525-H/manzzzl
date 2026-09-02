package com.manzzzzl.ai.design

/**
 * Final design preparation step before opening the 3D viewer.
 * Keeps geometry, user answers and Saudi design choices together.
 */
data class DesignGenerationSession(
    val questionnaire: DesignQuestionnaire,
    val appliedDesign: AppliedSaudiDesign? = null,
    val isReady: Boolean = false
)

object DesignGenerationValidator {
    fun validate(questionnaire: DesignQuestionnaire): Boolean {
        return !questionnaire.city.isNullOrBlank() &&
            questionnaire.floors != null
    }
}
