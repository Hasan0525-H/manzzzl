package com.manzzzl.ai.design

/**
 * Selects exterior facade direction based on Saudi context.
 * Keeps visual style separate from 3D geometry.
 */
object FacadeStyleGenerator {

    fun generate(city: String?, preference: String? = null): FacadeStyle {
        return when {
            preference != null -> FacadeStyle(preference)
            city?.contains("جدة") == true -> FacadeStyle("ساحلي مودرن")
            city?.contains("أبها") == true -> FacadeStyle("عسيري حديث")
            city?.contains("محايل") == true -> FacadeStyle("ترابي معاصر")
            city?.contains("جازان") == true -> FacadeStyle("استوائي سعودي")
            else -> FacadeStyle("مودرن سعودي")
        }
    }
}

data class FacadeStyle(
    val name: String
)
