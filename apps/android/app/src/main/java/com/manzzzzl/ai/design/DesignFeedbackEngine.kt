package com.manzzzl.ai.design

/**
 * Handles user feedback on generated designs.
 * Feedback changes visual generation choices while preserving the original plan geometry.
 */
object DesignFeedbackEngine {

    enum class FeedbackType {
        LIKE,
        DISLIKE,
        MODIFY
    }

    data class Feedback(
        val type: FeedbackType,
        val request: String? = null
    )

    fun shouldRegenerate(feedback: Feedback): Boolean {
        return feedback.type != FeedbackType.LIKE
    }

    fun preserveGeometry(): Boolean = true
}
