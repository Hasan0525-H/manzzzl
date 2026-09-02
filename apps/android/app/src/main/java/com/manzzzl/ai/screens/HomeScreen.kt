package com.manzzzl.ai.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onCreateProject: () -> Unit
) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("منزلي AI")
        Text("تحويل مخطط 2D إلى منزل ثلاثي الأبعاد")
        Button(onClick = onCreateProject) {
            Text("إنشاء مشروع جديد")
        }
    }
}
