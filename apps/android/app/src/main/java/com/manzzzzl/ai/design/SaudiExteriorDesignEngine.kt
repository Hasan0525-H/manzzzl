package com.manzzzl.ai.design

/**
 * Saudi exterior styling layer.
 * Keeps city-based visual decisions separate from 3D geometry.
 */
object SaudiExteriorDesignEngine {
    fun apply(city: String): SaudiExteriorProfile {
        return when (city) {
            "جدة" -> SaudiExteriorProfile(
                climate = "ساحلي",
                materials = listOf("حجر فاتح", "دهانات مقاومة للرطوبة"),
                shading = "تظليل وزجاج معالج"
            )
            "أبها" -> SaudiExteriorProfile(
                climate = "جبلي",
                materials = listOf("حجر عسيري", "ألوان طبيعية"),
                shading = "فتحات مدروسة"
            )
            "محايل عسير" -> SaudiExteriorProfile(
                climate = "دافئ",
                materials = listOf("خامات حرارية", "ألوان ترابية"),
                shading = "عزل وتظليل"
            )
            "جازان" -> SaudiExteriorProfile(
                climate = "رطب وحار",
                materials = listOf("خامات مقاومة للرطوبة"),
                shading = "تهوية طبيعية وتظليل"
            )
            else -> SaudiExteriorProfile(
                climate = "عام",
                materials = emptyList(),
                shading = "قياسي"
            )
        }
    }
}

data class SaudiExteriorProfile(
    val climate: String,
    val materials: List<String>,
    val shading: String
)
