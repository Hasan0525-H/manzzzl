package com.manzzzl.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.manzzzl.ai.design.DesignGenerationSession
import com.manzzzl.ai.threeD.model.ThreeDModel

@Composable
fun ThreeDViewerScreen(
    model: ThreeDModel? = null,
    session: DesignGenerationSession? = null
) {
    Column {
        Text("معاينة النموذج ثلاثي الأبعاد")

        if (session != null && session.isReady) {
            Text("جلسة التصميم جاهزة")
            Text("المدينة: ${session.questionnaire.city ?: "غير محددة"}")
        }

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
