package com.manzzzzl.ai.design

/**
 * User answers collected before generating the final Saudi design.
 * Only missing information should be requested by the question engine.
 */
data class DesignQuestionnaire(
    val floors: Int? = null,
    val landArea: Double? = null,
    val streetDirection: String? = null,
    val facadePreference: String? = null,
    val hasGarden: Boolean? = null,
    val hasAnnex: Boolean? = null,
    val hasBasement: Boolean? = null,
    val city: String? = null
)
