package com.manzzzlai.model

/**
 * Core project model for Manzzzl AI.
 * The floor plan remains the source of truth.
 */
data class HomeProject(
    val id: String,
    val city: String,
    val floors: Int,
    val planFilePath: String? = null,
    val processingProgress: Int = 0,
    val status: ProjectStatus = ProjectStatus.CREATED
)

enum class ProjectStatus {
    CREATED,
    PLAN_UPLOADED,
    ANALYZING,
    READY_FOR_RENDER,
    COMPLETED
}
