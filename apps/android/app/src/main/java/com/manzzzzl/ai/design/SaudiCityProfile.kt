package com.manzzzl.ai.design

enum class SaudiCity {
    JEDDAH,
    ABHA,
    MAHAYIL_ASIR,
    JAZAN
}

data class SaudiCityProfile(
    val city: SaudiCity,
    val climate: String,
    val facadeStyle: String,
    val materials: List<String>,
    val designNotes: List<String>
)

object SaudiCityProfiles {
    fun get(city: SaudiCity): SaudiCityProfile {
        return when (city) {
            SaudiCity.JEDDAH -> SaudiCityProfile(
                city,
                "ساحلي حار ورطب",
                "واجهة عصرية ساحلية مع ظلال وفتحات مدروسة",
                listOf("حجر طبيعي", "دهانات مقاومة للرطوبة", "زجاج مظلل"),
                listOf("تقليل اكتساب الحرارة", "تهوية جيدة", "ألوان فاتحة")
            )
            SaudiCity.ABHA -> SaudiCityProfile(
                city,
                "جبلي معتدل",
                "طابع جبلي حديث مستوحى من عسير",
                listOf("حجر عسيري", "خشب معالج", "أحجار طبيعية"),
                listOf("استغلال الإطلالات", "دفء الخامات", "شرفات مناسبة للمناخ")
            )
            SaudiCity.MAHAYIL_ASIR -> SaudiCityProfile(
                city,
                "دافئ مع طبيعة جبلية",
                "عمارة محلية مطورة تناسب البيئة",
                listOf("حجر طبيعي", "ألوان ترابية", "مواد عازلة"),
                listOf("عزل حراري", "ظلال خارجية", "بساطة الواجهة")
            )
            SaudiCity.JAZAN -> SaudiCityProfile(
                city,
                "ساحلي دافئ ورطب",
                "واجهة استوائية سعودية حديثة",
                listOf("مواد مقاومة للرطوبة", "حجر", "ألمنيوم مقاوم"),
                listOf("تهوية طبيعية", "تقليل الرطوبة", "مظلات خارجية")
            )
        }
    }
}
