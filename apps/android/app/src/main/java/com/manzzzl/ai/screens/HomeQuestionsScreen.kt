package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun HomeQuestionsScreen(
    onContinue: () -> Unit
) {
    val floors = remember { mutableStateOf("") }
    val city = remember { mutableStateOf("") }
    val area = remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(24.dp)) {
        Text("تجهيز معلومات المنزل")
        Text("أدخل البيانات قبل تحليل مخطط 2D")

        Spacer(modifier = Modifier.padding(8.dp))

        OutlinedTextField(
            value = floors.value,
            onValueChange = { floors.value = it },
            label = { Text("عدد الأدوار") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = city.value,
            onValueChange = { city.value = it },
            label = { Text("المدينة") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = area.value,
            onValueChange = { area.value = it },
            label = { Text("المساحة التقريبية") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = onContinue) {
            Text("متابعة لرفع المخطط")
        }
    }
}
