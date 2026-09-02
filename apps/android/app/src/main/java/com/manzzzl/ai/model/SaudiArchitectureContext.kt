package com.manzzzl.ai.model

/**
 * Local architectural context used during future 2D plan analysis.
 * This keeps Saudi environmental and regional considerations separate
 * from UI code.
 */
data class SaudiArchitectureContext(
    val city: String,
    val climate: String,
    val ventilationPriority: Boolean = true,
    val sunConsideration: Boolean = true,
    val notes: List<String> = emptyList()
)

object SaudiCities {
    val profiles = listOf(
        SaudiArchitectureContext(
            city = "Jeddah",
            climate = "Hot humid coastal climate"
        ),
        SaudiArchitectureContext(
            city = "Abha",
            climate = "Mountain mild climate"
        ),
        SaudiArchitectureContext(
            city = "Mahail Asir",
            climate = "Hot semi-arid mountain climate"
        ),
        SaudiArchitectureContext(
            city = "Jazan",
            climate = "Hot humid coastal climate"
        )
    )
}
