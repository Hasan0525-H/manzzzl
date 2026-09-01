package com.manzzzl.ai.models

/**
 * First project model for Manzzzl AI.
 * Represents a user's house design project.
 */
data class HouseProject(
    val id: String,
    val name: String,
    val city: String,
    val floors: Int,
    val planFile: String? = null,
    val status: String = "CREATED"
)
