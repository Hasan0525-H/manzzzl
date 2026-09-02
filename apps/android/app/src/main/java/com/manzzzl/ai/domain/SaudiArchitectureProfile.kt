package com.manzzzl.ai.domain

/**
 * Saudi architectural context used as a foundation for future 2D to 3D reconstruction.
 * This keeps regional environmental and architectural rules separate from UI logic.
 */
data class SaudiArchitectureProfile(
    val city: SaudiCity,
    val climate: String,
    val notes: List<String>
)

enum class SaudiCity {
    JEDDAH,
    ABHA,
    MAHAYIL_ASIR,
    JAZAN
}

object SaudiArchitectureProfiles {
    fun get(city: SaudiCity): SaudiArchitectureProfile {
        return when (city) {
            SaudiCity.JEDDAH -> SaudiArchitectureProfile(
                city,
                "coastal hot humid",
                listOf("consider ventilation", "consider solar exposure")
            )
            SaudiCity.ABHA -> SaudiArchitectureProfile(
                city,
                "mountain climate",
                listOf("consider terrain", "consider cooler conditions")
            )
            SaudiCity.MAHAYIL_ASIR -> SaudiArchitectureProfile(
                city,
                "warm inland climate",
                listOf("consider heat management")
            )
            SaudiCity.JAZAN -> SaudiArchitectureProfile(
                city,
                "hot coastal climate",
                listOf("consider humidity", "consider ventilation")
            )
        }
    }
}
