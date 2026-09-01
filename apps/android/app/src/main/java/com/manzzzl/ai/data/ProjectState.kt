package com.manzzzl.ai.data

// Temporary state holder for MVP flow.
data class ProjectState(
    val projectId: String? = null,
    val city: String = "",
    val floors: Int = 1,
    val planUploaded: Boolean = false,
    val progress: Int = 0
)
