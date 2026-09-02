package com.manzzzl.ai.domain

import com.manzzzl.ai.decor.DecorStyleProfile

object SaudiDecorResolver {
    fun resolve(city: String): DecorStyleProfile {
        return when (city.lowercase()) {
            "جدة" -> DecorStyleProfile(
                climate = "ساحلي رطب",
                style = "مودرن ساحلي",
                materials = listOf("حجر طبيعي", "ألوان محايدة", "تشطيبات مقاومة للرطوبة")
            )
            "أبها" -> DecorStyleProfile(
                climate = "جبلي معتدل",
                style = "مودرن دافئ",
                materials = listOf("خشب طبيعي", "حجر", "ألوان دافئة")
            )
            "محايل عسير" -> DecorStyleProfile(
                climate = "حار",
                style = "عصري محلي",
                materials = listOf("حجر محلي", "تشطيبات عملية", "تهوية طبيعية")
            )
            "جازان" -> DecorStyleProfile(
                climate = "ساحلي حار",
                style = "مودرن خفيف",
                materials = listOf("خامات مقاومة للرطوبة", "ألوان فاتحة", "إضاءة طبيعية")
            )
            else -> DecorStyleProfile(
                climate = "عام",
                style = "مودرن محايد",
                materials = listOf("ألوان محايدة", "خامات طبيعية")
            )
        }
    }
}
