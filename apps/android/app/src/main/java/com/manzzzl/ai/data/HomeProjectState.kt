package com.manzzzl.ai.data

import com.manzzzl.ai.model.HomeProject

/**
 * Application state for the project creation pipeline.
 */
class HomeProjectState(
    var project: HomeProject? = null,
    var currentStep: ProjectStep = ProjectStep.HOME
)

enum class ProjectStep {
    HOME,
    CREATE_PROJECT,
    QUESTIONS,
    UPLOAD_PLAN,
    ANALYSIS
}
