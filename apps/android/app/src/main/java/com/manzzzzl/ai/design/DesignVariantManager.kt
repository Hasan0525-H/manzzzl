package com.manzzzl.ai.design

/**
 * Manages alternative design proposals while preserving the original plan.
 * Variants change visual decisions only and do not alter the extracted geometry.
 */
object DesignVariantManager {

    fun createVariant(
        session: FinalDesignSession,
        facadeStyle: String,
        materials: List<String>
    ): DesignVariant {
        return DesignVariant(
            baseSession = session,
            facadeStyle = facadeStyle,
            materials = materials
        )
    }
}

data class DesignVariant(
    val baseSession: FinalDesignSession,
    val facadeStyle: String,
    val materials: List<String>
)
