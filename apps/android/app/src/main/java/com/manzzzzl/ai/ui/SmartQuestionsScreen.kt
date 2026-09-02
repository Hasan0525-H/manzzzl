package com.manzzzzl.ai.ui

import androidx.compose.runtime.Composable

/**
 * شاشة الأسئلة الذكية.
 * تعرض فقط الأسئلة التي يحددها SmartDesignQuestionEngine.
 */
@Composable
fun SmartQuestionsScreen(
    questions: List<String>,
    answers: Map<String, String> = emptyMap(),
    onAnswerChanged: (String, String) -> Unit = { _, _ -> },
    onComplete: () -> Unit = {}
) {
    // UI implementation will be connected with Compose components
    // after navigation integration.
}
