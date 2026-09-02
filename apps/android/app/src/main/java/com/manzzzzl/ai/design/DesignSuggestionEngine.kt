package com.manzzzzl.ai.design

import com.manzzzzl.ai.ui.SmartQuestionAnswer

/**
 * Provides fallback suggestions when the user selects "I don't know".
 * Suggestions are based on city context and available project data.
 */
object DesignSuggestionEngine {
    fun suggest(question: String, city: String?): String {
        return when {
            question.contains("واجهة") && city == "جدة" -> "واجهة فاتحة مع حلول مقاومة للرطوبة وتظليل زجاجي"
            question.contains("واجهة") && city == "أبها" -> "واجهة حجرية بلمسات عسيرية حديثة"
            question.contains("واجهة") && city == "جازان" -> "واجهة مقاومة للرطوبة مع تهوية طبيعية"
            question.contains("واجهة") && city == "محايل عسير" -> "واجهة بألوان ترابية وعزل حراري"
            question.contains("حديقة") -> "إضافة حديقة حسب مساحة الأرض والمخطط"
            else -> "اقتراح مناسب سيتم تحسينه بعد تحليل المخطط"
        }
    }
}
