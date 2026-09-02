package com.manzzzl.ai.analysis.model

data class Room(
    val name: String,
    val area: Float,
    val walls: List<Wall> = emptyList()
)
