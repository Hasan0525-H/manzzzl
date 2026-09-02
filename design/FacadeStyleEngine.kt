package design

/**
 * Selects exterior facade style based on Saudi city profile.
 */
class FacadeStyleEngine {
    fun generate(city: SaudiCityProfile): String {
        return when (city) {
            SaudiCityProfile.JEDDAH -> "Modern coastal facade"
            SaudiCityProfile.ABHA -> "Modern Asiri stone facade"
            SaudiCityProfile.MUHAYIL_ASIR -> "Thermal earth-tone facade"
            SaudiCityProfile.JAZAN -> "Ventilated tropical Saudi facade"
        }
    }
}
