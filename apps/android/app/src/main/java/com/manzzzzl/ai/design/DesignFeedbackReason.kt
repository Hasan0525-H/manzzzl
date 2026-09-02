package com.manzzzl.ai.design

/**
 * Reasons used when a user requests facade regeneration.
 * Keeps the original floor plan and geometry unchanged.
 */
enum class DesignFeedbackReason {
    COLOR,
    MATERIAL,
    FACADE_SHAPE,
    WINDOWS,
    SHADING,
    OTHER
}
