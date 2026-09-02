package com.manzzzl.ai.design

/**
 * Compares generated design variants without changing the original geometry.
 */
data class DesignVariantComparison(
    val variants: List<String>,
    val selectedVariant: String? = null
)

object DesignVariantComparisonEngine {
    fun compare(variants: List<String>): DesignVariantComparison {
        return DesignVariantComparison(variants = variants)
    }
}
