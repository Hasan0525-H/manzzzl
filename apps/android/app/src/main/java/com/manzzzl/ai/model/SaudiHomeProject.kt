package com.manzzzl.ai.model

/**
 * Project information with Saudi architectural context.
 * This model keeps local environmental and architectural inputs
 * available for the future 2D to 3D reconstruction pipeline.
 */
data class SaudiHomeProject(
    val name: String = "",
    val city: SaudiCity = SaudiCity.JEDDAH,
    val floors: Int = 1,
    val plotArea: Double? = null,
    val planPath: String? = null,
    val architectureProfile: SaudiArchitectureProfile = SaudiArchitectureProfile()
)

enum class SaudiCity {
    JEDDAH,
    ABHA,
    MAHAYIL_ASIR,
    JAZAN
}

data class SaudiArchitectureProfile(
    val climate: String = "",
    val ventilationPriority: Boolean = true,
    val solarConsideration: Boolean = true,
    val localArchitectureNotes: String = ""
)
