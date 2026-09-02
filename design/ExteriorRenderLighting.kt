package design

/**
 * Controls exterior visualization parameters for generated house designs.
 * Keeps rendering choices separate from geometry and city profiles.
 */
class ExteriorRenderLighting {
    enum class TimeOfDay {
        MORNING,
        AFTERNOON,
        SUNSET,
        NIGHT
    }

    data class LightingConfig(
        val timeOfDay: TimeOfDay,
        val naturalLight: Boolean = true,
        val shadowsEnabled: Boolean = true,
        val realisticMaterials: Boolean = true
    )

    fun defaultSaudiExterior(): LightingConfig {
        return LightingConfig(
            timeOfDay = TimeOfDay.AFTERNOON
        )
    }
}
