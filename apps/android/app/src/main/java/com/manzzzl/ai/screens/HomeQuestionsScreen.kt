package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeQuestionsScreen(
    onContinue: () -> Unit
) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("أسئلة تجهيز المشروع")
        Text("حدد معلومات المنزل قبل تحليل المخطط")
        Text("سيتم إضافة الحقول التفصيلية تدريجياً")

        Button(onClick = onContinue) {
            Text("متابعة")
        }
    }
}
