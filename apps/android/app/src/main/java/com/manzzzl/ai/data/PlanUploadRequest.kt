package com.manzzzl.ai.data

/**
 * Data model prepared for plan upload API.
 */
data class PlanUploadRequest(
    val projectId: String,
    val fileName: String,
    val fileType: String
)
