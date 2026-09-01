package com.manzzzl.ai.model

/**
 * First version of home project model.
 */
data class HomeProject(
    val name: String,
    val city: String,
    val floors: Int,
    val hasGarden: Boolean = false,
    val hasAnnex: Boolean = false
)
