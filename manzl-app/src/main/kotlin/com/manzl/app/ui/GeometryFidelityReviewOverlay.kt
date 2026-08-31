package com.manzl.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manzl.app.analysis.GeometryReviewItem
import com.manzl.app.analysis.GeometryReviewStore
import com.manzl.app.model.GeometryFidelityReport
import com.manzl.app.model.GeometryFidelityStatus

private val ReviewInk = Color(0xFF171717)
private val ReviewCanvas = Color(0xFFF7F5F1)
private val ReviewPass = Color(0xFF1F7A4D)
private val ReviewAmber = Color(0xFF9A6412)
private val ReviewBlocked = Color(0xFF9B302A)
private val ReviewBlue = Color(0xFF245A92)

/**
 * Global trust surface layered above ManzlExperience without weakening the one-button workflow.
 * Failed geometry opens automatically; successful geometry remains available as an explicit review
 * button before the user enters the walkthrough.
 */
@Composable
internal fun GeometryReviewHost(content: @Composable () -> Unit) {
    val review by GeometryReviewStore.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    var selectedFloor by remember { mutableIntStateOf(0) }

    LaunchedEffect(review.revision) {
        if (review.items.isEmpty()) {
            open = false
            selectedFloor = 0
        } else {
            val failing = review.items.indexOfFirst {
                it.plan.geometryFidelity.status != GeometryFidelityStatus.PASS
            }
            selectedFloor = if (failing >= 0) failing else 0
            if (review.autoOpen) open = true
        }
    }

    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            content()

            if (review.items.isNotEmpty() && !open) {
                val selected = review.items[selectedFloor.coerceIn(0, review.items.lastIndex)]
                val report = selected.plan.geometryFidelity
                Button(
                    onClick = { open = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = statusColor(report.status),
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        text = "مراجعة التطابق • ${percent(report.score)}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (open && review.items.isNotEmpty()) {
                GeometryReviewScreen(
                    items = review.items,
                    selectedFloor = selectedFloor.coerceIn(0, review.items.lastIndex),
                    onSelectFloor = { selectedFloor = it },
                    onClose = { open = false },
                    onClear = {
                        GeometryReviewStore.clearVisible()
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun GeometryReviewScreen(
    items: List<GeometryReviewItem>,
    selectedFloor: Int,
    onSelectFloor: (Int) -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    val item = items[selectedFloor]
    val report = item.plan.geometryFidelity
    val blocking = items.any { it.plan.geometryFidelity.status != GeometryFidelityStatus.PASS }
    val statusColor = statusColor(report.status)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = ReviewCanvas,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (blocking) "مراجعة هندسة المخطط • 3D متوقف" else "مراجعة هندسة المخطط",
                        color = ReviewInk,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    )
                    Text(
                        text = if (blocking) {
                            "لن يبدأ بناء المنزل من أي دور غير مطابق. الطبقة الملونة أدناه هي الهندسة المستخرجة فوق صورتك الأصلية."
                        } else {
                            "كل الأدوار اجتازت بوابة المطابقة. يمكنك فحص الجدران والفتحات قبل بدء الجولة."
                        },
                        color = Color(0xFF68635D),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onClose) {
                    Text("إغلاق")
                }
            }

            if (items.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items.forEachIndexed { index, floor ->
                        val label = reviewFloorNameArabic(index)
                        val passed = floor.plan.geometryFidelity.status == GeometryFidelityStatus.PASS
                        if (index == selectedFloor) {
                            Button(
                                onClick = { onSelectFloor(index) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = statusColor(floor.plan.geometryFidelity.status),
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text("$label • ${if (passed) "PASS" else "راجع"}")
                            }
                        } else {
                            OutlinedButton(onClick = { onSelectFloor(index) }) {
                                Text("$label • ${if (passed) "PASS" else "راجع"}")
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = reviewFloorNameArabic(item.floorIndex),
                            modifier = Modifier.weight(1f),
                            color = ReviewInk,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = statusColor.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = statusArabic(report.status),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }

                    Image(
                        bitmap = item.overlay.asImageBitmap(),
                        contentDescription = "تطابق الهندسة مع ${reviewFloorNameArabic(item.floorIndex)}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 190.dp, max = 430.dp),
                        contentScale = ContentScale.Fit,
                    )

                    Text(
                        text = "لون الجدران: حالة المطابقة • أزرق: محاور الأبواب • سماوي: محاور النوافذ. المخطط الأصلي لا يتم تعديله.",
                        color = ReviewBlue,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "الجودة",
                    value = percent(report.score),
                    accent = statusColor,
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "تغطية الجدران",
                    value = percent(report.wallCoverage),
                    accent = statusColor,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "دقة الموضع",
                    value = percent(report.wallPrecision),
                    accent = statusColor,
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "دعم النهايات",
                    value = percent(report.endpointSupport),
                    accent = statusColor,
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = statusColor.copy(alpha = 0.09f),
            ) {
                Column(
                    modifier = Modifier.padding(13.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (report.status == GeometryFidelityStatus.PASS) {
                            "تشخيص المطابقة"
                        } else {
                            "لماذا توقف البناء؟"
                        },
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                    )
                    geometryAdviceArabic(report).forEach { advice ->
                        Text(
                            text = "• $advice",
                            color = ReviewInk,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (blocking) {
                Text(
                    text = "قاعدة منزل الحالية: لا يوجد زر لتجاوز هذه النتيجة. التصحيح القادم سيعدل الهندسة ثم يعيد قياسها؛ لن يحول REVIEW/BLOCKED إلى PASS شكلياً.",
                    color = ReviewBlocked,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("إخفاء نتيجة المراجعة")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier,
    label: String,
    value: String,
    accent: Color,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
    ) {
        Column(modifier = Modifier.padding(11.dp)) {
            Text(label, color = Color(0xFF716C65), style = MaterialTheme.typography.labelSmall)
            Text(value, color = accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}

private fun geometryAdviceArabic(report: GeometryFidelityReport): List<String> {
    val advice = ArrayList<String>()
    if (report.wallCoverage < 0.82f) {
        advice += "تغطية الجدران منخفضة: توجد أجزاء خطية في الرسم لم تستعدها الهندسة الحالية بما يكفي."
    }
    if (report.wallPrecision < 0.84f) {
        advice += "دقة الموضع منخفضة: بعض وجوه الجدران أو سماكاتها لا تقع فوق الحبر البنيوي بدقة كافية."
    }
    if (report.endpointSupport < 0.75f) {
        advice += "دعم النهايات منخفض: نهايات الجدران أو التقاطعات تحتاج استخراجاً/تصحيحاً أدق."
    }
    if (advice.isEmpty()) {
        advice += if (report.status == GeometryFidelityStatus.PASS) {
            "الجدران المستخرجة اجتازت حدود التغطية والدقة ودعم النهايات المطلوبة لبناء 3D."
        } else {
            "النتيجة قريبة من الحدود لكنها لم تحقق كل شروط PASS في الوقت نفسه؛ لا يتم تجاوز الشرط المركب."
        }
    }
    return advice
}

private fun statusArabic(status: GeometryFidelityStatus): String = when (status) {
    GeometryFidelityStatus.PASS -> "PASS • مطابق"
    GeometryFidelityStatus.REVIEW_REQUIRED -> "مراجعة مطلوبة"
    GeometryFidelityStatus.BLOCKED -> "موقوف"
    GeometryFidelityStatus.UNKNOWN -> "غير متحقق"
}

private fun statusColor(status: GeometryFidelityStatus): Color = when (status) {
    GeometryFidelityStatus.PASS -> ReviewPass
    GeometryFidelityStatus.REVIEW_REQUIRED -> ReviewAmber
    GeometryFidelityStatus.BLOCKED -> ReviewBlocked
    GeometryFidelityStatus.UNKNOWN -> Color(0xFF666666)
}

private fun percent(value: Float): String = "${(value.coerceIn(0f, 1f) * 100f).toInt()}%"

private fun reviewFloorNameArabic(index: Int): String = when (index) {
    0 -> "الدور الأرضي"
    1 -> "الدور الأول"
    2 -> "الدور الثاني"
    3 -> "الدور الثالث"
    4 -> "الدور الرابع"
    5 -> "الدور الخامس"
    else -> "الدور ${index + 1}"
}
