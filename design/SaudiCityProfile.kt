package design

/**
 * Saudi location profile used by the exterior design pipeline.
 * Keeps climate and architectural preferences separate from geometry generation.
 */
data class SaudiCityProfile(
    val city: SaudiCity,
    val climateNotes: List<String>,
    val exteriorStyle: String,
    val materialHints: List<String>
)

enum class SaudiCity {
    JEDDAH,
    ABHA,
    MUHAYIL_ASIR,
    JAZAN
}

object SaudiCityProfiles {
    fun get(city: SaudiCity): SaudiCityProfile = when (city) {
        SaudiCity.JEDDAH -> SaudiCityProfile(
            city,
            listOf("humidity resistance", "solar shading"),
            "modern coastal",
            listOf("light stone", "treated glass")
        )
        SaudiCity.ABHA -> SaudiCityProfile(
            city,
            listOf("mountain climate", "natural materials"),
            "modern asiri",
            listOf("asiri stone", "natural textures")
        )
        SaudiCity.MUHAYIL_ASIR -> SaudiCityProfile(
            city,
            listOf("heat reduction", "thermal insulation"),
            "contemporary earth tones",
            listOf("thermal finishes", "stone accents")
        )
        SaudiCity.JAZAN -> SaudiCityProfile(
            city,
            listOf("high humidity", "natural ventilation"),
            "tropical saudi modern",
            listOf("weather resistant finishes", "shading elements")
        )
    }
}
