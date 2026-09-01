package com.manzl.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manzl.app.analysis.MetricScaleReviewApplier
import com.manzl.app.model.BuildingPlan

private val ScaleWarningSurface = Color(0xFFFFF4DC)
private val ScaleWarningInk = Color(0xFF7A5312)
private val ScaleAppliedSurface = Color(0xFFE8F2FF)
private val ScaleAppliedInk = Color(0xFF245A92)

@Composable
internal fun MetricScaleReviewCard(
    building: BuildingPlan,
    onApply: (Map<String, Float>) -> Unit,
) {
    val reviewLevels = building.levels.filter { MetricScaleReviewApplier.needsReview(it.plan) }
    if (reviewLevels.isEmpty()) return

    var inputByLevel by remember(building) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val parsed = reviewLevels.mapNotNull { level ->
        val value = inputByLevel[level.id].orEmpty().toMetricFloatOrNull() ?: return@mapNotNull null
        if (!MetricScaleReviewApplier.isPlausibleLongSideMeters(value)) return@mapNotNull null
        level.id to value
    }.toMap()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ScaleWarningSurface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Straighten, contentDescription = null, tint = ScaleWarningInk)
                Spacer(Modifier.size(7.dp))
                Text(
                    "مقياس المخطط يحتاج تأكيد",
                    color = ScaleWarningInk,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                "لم أجد بُعداً مطبوعاً موثوقاً بما يكفي في بعض الأدوار. إذا كنت تعرف طول الضلع الأكبر بالمتر أدخله هنا؛ وإلا يمكنك المتابعة بالمقياس التقديري الحالي.",
                color = ScaleWarningInk,
                style = MaterialTheme.typography.bodySmall,
            )

            reviewLevels.forEach { level ->
                val current = MetricScaleReviewApplier.currentLongSideMeters(level.plan)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${scaleFloorNameArabic(level.levelIndex)} • التقدير الحالي ${"%.2f".format(current)} م • ثقة ${(level.plan.scaleConfidence * 100).toInt()}%",
                        color = ScaleWarningInk,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = inputByLevel[level.id].orEmpty(),
                        onValueChange = { raw ->
                            inputByLevel = inputByLevel + (level.id to raw.take(8))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("طول الضلع الأكبر بالمتر") },
                        placeholder = { Text("مثال: 18.50") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }

            Button(
                onClick = { if (parsed.isNotEmpty()) onApply(parsed) },
                enabled = parsed.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ScaleWarningInk,
                    contentColor = Color.White,
                ),
            ) {
                Text("تطبيق المقاس المؤكد على نسخة البناء")
            }
        }
    }
}

@Composable
internal fun MetricScaleAppliedNotice(onRevert: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ScaleAppliedSurface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "تم اعتماد القياس الذي أدخلته",
                color = ScaleAppliedInk,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "تم تغيير مقياس X/Z فقط مع الحفاظ على شكل المخطط ونسبه. ارتفاعات الجدران والنوافذ وارتفاع الدور لم تُضرب في معامل المقياس.",
                color = ScaleAppliedInk,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onRevert,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFDCE9F7),
                    contentColor = ScaleAppliedInk,
                ),
            ) {
                Text("إلغاء تصحيح القياس والعودة للتقدير")
            }
        }
    }
}

private fun String.toMetricFloatOrNull(): Float? {
    if (isBlank()) return null
    val normalized = buildString(length) {
        this@toMetricFloatOrNull.forEach { char ->
            append(
                when (char) {
                    '٠', '۰' -> '0'
                    '١', '۱' -> '1'
                    '٢', '۲' -> '2'
                    '٣', '۳' -> '3'
                    '٤', '۴' -> '4'
                    '٥', '۵' -> '5'
                    '٦', '۶' -> '6'
                    '٧', '۷' -> '7'
                    '٨', '۸' -> '8'
                    '٩', '۹' -> '9'
                    '٫', ',' -> '.'
                    else -> char
                }
            )
        }
    }
    return normalized.trim().toFloatOrNull()
}

private fun scaleFloorNameArabic(index: Int): String = when (index) {
    0 -> "الدور الأرضي"
    1 -> "الدور الأول"
    2 -> "الدور الثاني"
    3 -> "الدور الثالث"
    4 -> "الدور الرابع"
    5 -> "الدور الخامس"
    else -> "الدور ${index + 1}"
}
