package com.manzzzl.ai.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CreateHomeProjectScreen(
    onContinue: () -> Unit
) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("إنشاء مشروع منزل جديد")
        Text("اختر معلومات المنزل قبل رفع المخطط")
        Text("الأدوار: دور واحد أو دورين")
        Text("المدن: جدة، أبها، محايل عسير، جازان")
        Button(onClick = onContinue) {
            Text("متابعة")
        }
    }
}
