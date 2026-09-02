package com.manzzzl.ai.model

/**
 * Holds the user's house project flow data.
 */
data class ProjectState(
    val planUri: String? = null,
    val city: String = "",
    val floors: String = "",
    val analysisProgress: Int = 0,
    val generatedModelReady: Boolean = false
)
