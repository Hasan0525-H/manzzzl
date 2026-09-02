package com.manzzzl.ai.ui

/**
 * Stores user answers collected from smart design questions.
 * Keeps questionnaire data separate from analysis and 3D generation.
 */
data class SmartQuestionAnswer(
    val questionId: String,
    val answer: String
)
