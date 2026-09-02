package com.manzzzl.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.manzzzl.ai.threeD.model.ThreeDModel

@Composable
fun ThreeDViewerScreen(model: ThreeDModel? = null) {
    Column {
        Text("معاينة النموذج ثلاثي الأبعاد")

        if (model == null) {
            Text("بانتظار نتيجة التحليل")
        } else {
            Text("تم تجهيز النموذج")
            Text("الجدران: ${model.walls.size}")
            Text("الغرف: ${model.rooms.size}")
            Text("الفتحات: ${model.openings.size}")
        }

        Button(onClick = { }) {
            Text("تدوير النموذج")
        }
    }
}
