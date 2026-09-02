package com.manzzzl.ai.model

/**
 * Single flow object connecting upload, analysis and 3D generation.
 */
data class ProjectSession(
    val project: HouseProject,
    val stage: ProjectStage = ProjectStage.DRAFT,
    val geometryReady: Boolean = false
)
