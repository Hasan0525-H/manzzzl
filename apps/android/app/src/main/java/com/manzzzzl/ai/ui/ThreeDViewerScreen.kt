package com.manzzzl.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ThreeDViewerScreen() {
    Column {
        Text("معاينة النموذج ثلاثي الأبعاد")
        Text("النموذج جاهز للربط مع محرك العرض")
        Button(onClick = { }) {
            Text("تدوير النموذج")
        }
    }
}
