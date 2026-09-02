package com.manzzzl.ai.analysis.model

data class Wall(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val height: Float = 3f
)
