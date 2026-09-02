package com.manzzzl.ai.design

/**
 * Final assembled design state before rendering.
 * Keeps geometry, Saudi styling and facade decisions together.
 */
data class FinalDesignSession(
    val generationSession: DesignGenerationSession,
    val facadeDesign: FacadeDesignResult? = null
)
