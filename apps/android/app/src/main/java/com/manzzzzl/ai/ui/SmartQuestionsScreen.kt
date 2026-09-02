package com.manzzzzl.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    Column {
        questions.forEach { question ->
            OutlinedTextField(
                value = answers[question] ?: "",
                onValueChange = { value ->
                    onAnswerChanged(question, value)
                },
                label = { Text(question) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(onClick = onComplete) {
            Text("متابعة التصميم")
        }
    }
}
