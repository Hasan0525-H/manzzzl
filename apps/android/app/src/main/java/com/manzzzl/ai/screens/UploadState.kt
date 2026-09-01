package com.manzzzl.ai.screens

/**
 * Upload state model for house plan processing.
 */
sealed class UploadState {
    object Idle : UploadState()
    object Uploading : UploadState()
    object Uploaded : UploadState()
    data class Error(val message: String) : UploadState()
}
