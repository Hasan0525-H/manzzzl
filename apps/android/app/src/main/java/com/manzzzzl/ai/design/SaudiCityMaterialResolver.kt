package com.manzzzl.ai.design

/**
 * Resolves exterior material decisions from Saudi city context.
 * Keeps visual styling independent from 3D geometry.
 */
object SaudiCityMaterialResolver {
    fun resolve(city: String): SaudiExteriorMaterialProfile {
        return when (city.trim()) {
            "جدة" -> SaudiExteriorMaterialProfile(
                facadeMaterial = "حجر فاتح مع معالجة رطوبة",
                palette = "ألوان ساحلية هادئة",
                climateStrategy = "تظليل ومقاومة الرطوبة"
            )
            "أبها" -> SaudiExteriorMaterialProfile(
                facadeMaterial = "حجر عسيري وخامات طبيعية",
                palette = "ألوان جبلية ترابية",
                climateStrategy = "عزل حراري وفتحات مدروسة"
            )
            "محايل عسير" -> SaudiExteriorMaterialProfile(
                facadeMaterial = "حجر وخامات مقاومة للحرارة",
                palette = "ألوان ترابية",
                climateStrategy = "تقليل اكتساب الحرارة"
            )
            "جازان" -> SaudiExteriorMaterialProfile(
                facadeMaterial = "خامات مقاومة للرطوبة",
                palette = "ألوان فاتحة",
                climateStrategy = "تهوية وتظليل خارجي"
            )
            else -> SaudiExteriorMaterialProfile(
                facadeMaterial = "خامات سعودية حديثة",
                palette = "محايدة",
                climateStrategy = "حلول مناخية عامة"
            )
        }
    }
}

data class SaudiExteriorMaterialProfile(
    val facadeMaterial: String,
    val palette: String,
    val climateStrategy: String
)
