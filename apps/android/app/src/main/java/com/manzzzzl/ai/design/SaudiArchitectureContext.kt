package com.manzzzl.ai.design

/**
 * Context layer for Saudi architectural styles.
 * Keeps design decisions separate from the 3D geometry.
 */
data class SaudiArchitectureContext(
    val city: String,
    val style: String,
    val climate: String,
    val materials: List<String>
)
