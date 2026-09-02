package com.manzzzz.ai.design

/**
 * Applies regeneration requests without rebuilding the complete house.
 * Geometry stays unchanged unless explicitly requested.
 */
object RegenerationScopeApplier {
    fun apply(scope: RegenerationScope): String {
        return when (scope) {
            RegenerationScope.COLORS_ONLY -> "Update exterior colors only"
            RegenerationScope.MATERIALS_ONLY -> "Update facade materials only"
            RegenerationScope.FACADE_ONLY -> "Regenerate facade only"
            RegenerationScope.OPENINGS_ONLY -> "Update windows and openings only"
            RegenerationScope.SHADING_ONLY -> "Update shading elements only"
        }
    }
}

enum class RegenerationScope {
    COLORS_ONLY,
    MATERIALS_ONLY,
    FACADE_ONLY,
    OPENINGS_ONLY,
    SHADING_ONLY
}
