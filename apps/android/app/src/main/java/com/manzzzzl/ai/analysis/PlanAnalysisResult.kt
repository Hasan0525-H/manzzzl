package com.manzzzl.ai.analysis

 data class PlanAnalysisResult(
    val projectId: String,
    val wallsDetected: Boolean,
    val roomsDetected: Boolean,
    val openingsDetected: Boolean
)
