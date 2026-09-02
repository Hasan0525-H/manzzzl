package com.manzzzl.ai.design

/**
 * Connects user feedback with facade-only regeneration.
 * Keeps floor geometry and original plan unchanged.
 */
object DesignRegenerationFeedbackBridge {
    fun shouldRegenerate(feedback: DesignFeedback): Boolean {
        return feedback != DesignFeedback.LIKE
    }
}

enum class DesignFeedback {
    LIKE,
    DISLIKE,
    MODIFY
}
