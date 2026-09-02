package com.manzzzl.ai.design

/**
 * Maps user feedback reasons to the smallest possible regeneration scope.
 * The original plan geometry remains unchanged.
 */
object FeedbackRegenerationMapper {
    fun map(reason: DesignFeedbackReason): RegenerationScope {
        return when (reason) {
            DesignFeedbackReason.COLOR -> RegenerationScope.COLORS_ONLY
            DesignFeedbackReason.MATERIAL -> RegenerationScope.MATERIALS_ONLY
            DesignFeedbackReason.FACADE_SHAPE -> RegenerationScope.FACADE_ONLY
            DesignFeedbackReason.WINDOWS -> RegenerationScope.OPENINGS_ONLY
            DesignFeedbackReason.SHADING -> RegenerationScope.SHADING_ONLY
            DesignFeedbackReason.OTHER -> RegenerationScope.FULL_FACADE
        }
    }
}

enum class RegenerationScope {
    COLORS_ONLY,
    MATERIALS_ONLY,
    FACADE_ONLY,
    OPENINGS_ONLY,
    SHADING_ONLY,
    FULL_FACADE
}
