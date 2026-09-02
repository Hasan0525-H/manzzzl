package design

/**
 * Resolves exterior materials based on selected Saudi city profile.
 */
class MaterialResolver {
    fun resolve(city: SaudiCity): List<String> {
        return when (city) {
            SaudiCity.JEDDAH -> listOf("coastal_paint", "treated_glass", "aluminum_shading")
            SaudiCity.ABHA -> listOf("asir_stone", "natural_texture", "thermal_insulation")
            SaudiCity.MUHAYIL_ASIR -> listOf("earth_tone_finish", "thermal_coating", "shade_elements")
            SaudiCity.JAZAN -> listOf("humidity_resistant_finish", "ventilation_elements", "external_shading")
        }
    }
}
