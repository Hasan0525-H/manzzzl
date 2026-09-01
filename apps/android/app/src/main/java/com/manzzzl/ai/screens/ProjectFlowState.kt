package com.manzzzl.ai.screens

// First application flow state model.
// Future implementation will connect these states to real backend jobs.

enum class ProjectFlowState {
    LOGIN,
    CREATE_PROJECT,
    UPLOAD_PLAN,
    ANALYZING,
    QUESTIONS,
    CONFIRMATION,
    BUILDING_3D,
    RESULT
}
