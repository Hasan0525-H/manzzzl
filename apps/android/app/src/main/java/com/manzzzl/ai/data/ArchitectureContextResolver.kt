package com.manzzzl.ai.data

import com.manzzzl.ai.model.SaudiArchitectureContext

object ArchitectureContextResolver {
    fun resolve(city: String): SaudiArchitectureContext {
        return when (city.trim()) {
            "جدة" -> SaudiArchitectureContext(
                city = "جدة",
                climate = "ساحلي حار ورطب",
                ventilationPriority = true,
                sunConsideration = true
            )
            "أبها" -> SaudiArchitectureContext(
                city = "أبها",
                climate = "جبلي معتدل",
                ventilationPriority = true,
                sunConsideration = true
            )
            "محايل عسير" -> SaudiArchitectureContext(
                city = "محايل عسير",
                climate = "دافئ وجاف نسبياً",
                ventilationPriority = true,
                sunConsideration = true
            )
            "جازان" -> SaudiArchitectureContext(
                city = "جازان",
                climate = "ساحلي حار ورطب",
                ventilationPriority = true,
                sunConsideration = true
            )
            else -> SaudiArchitectureContext(
                city = city,
                climate = "غير محدد",
                ventilationPriority = true,
                sunConsideration = true
            )
        }
    }
}
