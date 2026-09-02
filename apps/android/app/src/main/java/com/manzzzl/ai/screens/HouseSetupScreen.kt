package com.manzzzl.ai.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun HouseSetupScreen(onContinue: () -> Unit = {}) {
    Text("إعداد المنزل")
    Text("اختر المدينة وعدد الأدوار")
    Button(onClick = onContinue) {
        Text("متابعة")
    }
}
