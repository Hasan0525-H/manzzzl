package com.manzzzl.ai.analysis.model

enum class OpeningType {
    DOOR,
    WINDOW
}

data class Opening(
    val type: OpeningType,
    val x: Float,
    val y: Float,
    val width: Float
)
