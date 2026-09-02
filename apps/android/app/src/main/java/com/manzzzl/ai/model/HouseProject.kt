package com.manzzzl.ai.model

/**
 * Unified saved house project model.
 * Keeps project identity, plan data and Saudi design context inputs together.
 */
data class HouseProject(
    val id: String = "",
    val name: String = "",
    val city: String = "",
    val floors: Int = 1,
    val floorPlanPath: String? = null,
    val hasGarden: Boolean = false,
    val hasAnnex: Boolean = false
)
