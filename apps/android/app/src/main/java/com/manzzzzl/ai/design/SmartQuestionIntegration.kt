package com.manzzzz.ai.design

/**
 * Connects smart question answers with the design pipeline.
 * Keeps user choices separate from geometry generation.
 */
class SmartQuestionIntegration {
    fun applyAnswer(
        questionnaire: DesignQuestionnaire,
        answer: SmartQuestionAnswer
    ): DesignQuestionnaire {
        return questionnaire.copy(
            city = answer.city ?: questionnaire.city,
            floors = answer.floors ?: questionnaire.floors,
            streetDirection = answer.streetDirection ?: questionnaire.streetDirection,
            facadePreference = answer.facadePreference ?: questionnaire.facadePreference,
            hasGarden = answer.hasGarden ?: questionnaire.hasGarden,
            hasAnnex = answer.hasAnnex ?: questionnaire.hasAnnex,
            hasBasement = answer.hasBasement ?: questionnaire.hasBasement
        )
    }

    fun isReadyForDesign(questionnaire: DesignQuestionnaire): Boolean {
        return questionnaire.city != null &&
            questionnaire.floors != null
    }
}
