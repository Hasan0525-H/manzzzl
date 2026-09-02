package com.manzzzz.ai.design

/**
 * Creates a validated design session before entering the 3D viewer.
 */
object DesignSessionFactory {
    fun create(questionnaire: DesignQuestionnaire): DesignGenerationSession {
        val ready = DesignGenerationValidator.validate(questionnaire)

        return DesignGenerationSession(
            questionnaire = questionnaire,
            isReady = ready
        )
    }
}
