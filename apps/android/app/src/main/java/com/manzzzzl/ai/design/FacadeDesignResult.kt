package com.manzzzl.ai.design

/**
 * Final exterior facade decision prepared before rendering.
 * Furniture is intentionally excluded.
 */
data class FacadeDesignResult(
    val city: String,
    val style: String,
    val materials: List<String>,
    val climateStrategy: List<String>
)
