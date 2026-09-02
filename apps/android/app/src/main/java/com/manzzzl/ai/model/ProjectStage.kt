package com.manzzzl.ai.model

/** Current lifecycle stage of a house generation project. */
enum class ProjectStage {
    DRAFT,
    UPLOADED,
    ANALYZING,
    GENERATING_3D,
    COMPLETED,
    FAILED
}
