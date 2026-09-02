package com.manzzzl.ai.design

/**
 * Exterior and interior finish profile.
 * Furniture is intentionally excluded.
 */
data class MaterialProfile(
    val exteriorMaterials: List<String>,
    val colors: List<String>,
    val decorativeElements: List<String>
)
