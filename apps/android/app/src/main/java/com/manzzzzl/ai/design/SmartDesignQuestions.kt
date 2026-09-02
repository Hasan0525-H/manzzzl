package com.manzzzl.ai.design

/**
 * Questions required before generating a Saudi house design.
 * Used to complete missing information from a 2D plan.
 */
data class SmartDesignQuestion(
    val id: String,
    val title: String,
    val required: Boolean = true
)

object SmartDesignQuestions {
    val defaultQuestions = listOf(
        SmartDesignQuestion("floors", "كم عدد أدوار المنزل؟"),
        SmartDesignQuestion("landArea", "ما مساحة الأرض التقريبية؟"),
        SmartDesignQuestion("streetDirection", "ما اتجاه الشارع؟"),
        SmartDesignQuestion("style", "ما نوع الواجهة المطلوبة؟"),
        SmartDesignQuestion("garden", "هل يوجد حديقة أو مساحة خارجية؟"),
        SmartDesignQuestion("annex", "هل يوجد ملحق أو مجلس خارجي؟"),
        SmartDesignQuestion("basement", "هل يوجد قبو؟")
    )
}
