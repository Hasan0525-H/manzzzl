package com.manzzzl.ai.design

/**
 * Determines which questions are still needed before generating the design.
 * Questions that can be inferred from the floor plan should be skipped.
 */
object SmartDesignQuestionEngine {

    fun missingQuestions(
        hasFloors: Boolean,
        hasLandArea: Boolean,
        hasStreetDirection: Boolean,
        hasFacadePreference: Boolean
    ): List<String> {
        val questions = mutableListOf<String>()

        if (!hasFloors) questions.add("عدد الأدوار")
        if (!hasLandArea) questions.add("مساحة الأرض")
        if (!hasStreetDirection) questions.add("اتجاه الشارع")
        if (!hasFacadePreference) questions.add("نوع الواجهة")

        return questions
    }
}
