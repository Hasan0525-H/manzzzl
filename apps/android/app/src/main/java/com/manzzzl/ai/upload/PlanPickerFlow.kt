package com.manzzzl.ai.upload

/**
 * Central rules for accepting architectural plan inputs.
 * UI layers can use this before sending files into the analysis pipeline.
 */
class PlanPickerFlow {

    private val supported = setOf(
        "image/png",
        "image/jpeg",
        "application/pdf"
    )

    fun supportedTypes(): List<String> = supported.toList()

    fun canAccept(mimeType: String?): Boolean {
        return mimeType != null && mimeType in supported
    }

    fun validate(path: String?): Boolean {
        return !path.isNullOrBlank()
    }
}
