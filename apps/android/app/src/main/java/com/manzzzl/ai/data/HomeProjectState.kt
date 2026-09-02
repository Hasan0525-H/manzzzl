package com.manzzzl.ai.data

import com.manzzzl.ai.model.HomeProject

/**
 * Temporary application state for the project creation pipeline.
 */
data class HomeProjectState(
    val project: HomeProject? = null,
    val currentStep: ProjectStep = ProjectStep.HOME
)

enum class ProjectStep {
    HOME,
    CREATE_PROJECT,
    QUESTIONS,
    UPLOAD_PLAN,
    ANALYSIS
}
