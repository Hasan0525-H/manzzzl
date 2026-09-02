package com.manzzzl.ai.data

/**
 * Core project model for the 2D plan to 3D workflow.
 */
data class HomeProject(
    val name: String = "",
    val floors: String = "دور واحد",
    val city: String = "",
    val planPath: String? = null
)
