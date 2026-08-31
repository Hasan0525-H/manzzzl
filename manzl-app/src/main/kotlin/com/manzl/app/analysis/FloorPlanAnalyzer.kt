package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.AnalysisUpdate
import com.manzl.app.model.FloorPlan

fun interface ProgressSink {
    fun onUpdate(update: AnalysisUpdate)
}

interface FloorPlanAnalyzer {
    suspend fun analyze(
        bitmap: Bitmap,
        progress: ProgressSink = ProgressSink {},
    ): FloorPlan
}
