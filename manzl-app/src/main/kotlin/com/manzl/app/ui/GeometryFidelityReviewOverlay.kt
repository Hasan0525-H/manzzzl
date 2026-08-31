package com.manzl.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manzl.app.analysis.GeometryCorrection
import com.manzl.app.analysis.GeometryReviewItem
import com.manzl.app.analysis.GeometryReviewStore
import com.manzl.app.analysis.PlanRasterTransform
import com.manzl.app.analysis.WallEndpoint
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityIssueKind
import com.manzl.app.model.GeometryFidelityReport
import com.manzl.app.model.GeometryFidelityStatus
import com.manzl.app.model.Vec2
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val ReviewInk = Color(0xFF171717)
private val ReviewCanvas = Color(0xFFF7F5F1)
private val ReviewPass = Color(0xFF1F7A4D)
private val ReviewAmber = Color(0xFF9A6412)
private val ReviewBlocked = Color(0xFF9B302A)
private val ReviewBlue = Color(0xFF245A92)
private val ReviewMissing = Color(0xFFCD1948)
private val ReviewExtra = Color(0xFF582BAB)

private enum class CorrectionTool {
    NONE,
    ADD_WALL,
    DELETE_WALL,
    MOVE_ENDPOINT,
}

private data class SelectedEndpoint(
    val wallIndex: Int,
    val endpoint: WallEndpoint,
    val point: Vec2,
)

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
            selectedFloor = if (failing >= 0) failing else selectedFloor.coerceIn(0, review.items.lastIndex)
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
    val scope = rememberCoroutineScope()
    val item = items[selectedFloor]
    val report = item.plan.geometryFidelity
    val blocking = items.any { it.plan.geometryFidelity.status != GeometryFidelityStatus.PASS }
    val statusColor = statusColor(report.status)
    val missingRegions = report.issues.count { it.kind == GeometryFidelityIssueKind.MISSING_SOURCE }
    val extraRegions = report.issues.count { it.kind == GeometryFidelityIssueKind.EXTRA_GEOMETRY }
    val correctionCount = GeometryReviewStore.correctionCount(item.source)

    var tool by remember { mutableStateOf(CorrectionTool.NONE) }
    var firstWallPoint by remember { mutableStateOf<Vec2?>(null) }
    var selectedEndpoint by remember { mutableStateOf<SelectedEndpoint?>(null) }
    var correctionBusy by remember { mutableStateOf(false) }
    var correctionMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedFloor) {
        tool = CorrectionTool.NONE
        firstWallPoint = null
        selectedEndpoint = null
        correctionMessage = null
    }

    fun submit(correction: GeometryCorrection, successMessage: String) {
        if (correctionBusy) return
        correctionBusy = true
        scope.launch {
            val before = GeometryReviewStore.correctionCount(item.source)
            val updated = GeometryReviewStore.applyCorrection(item.floorIndex, correction)
            val after = GeometryReviewStore.correctionCount(item.source)
            correctionMessage = if (updated != null && after > before) {
                successMessage
            } else {
                "لم أقبل التعديل لأنه غير صالح هندسياً أو خارج حدود المخطط."
            }
            correctionBusy = false
            firstWallPoint = null
            selectedEndpoint = null
        }
    }

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

                    GeometryCorrectionImage(
                        item = item,
                        enabled = blocking && !correctionBusy && tool != CorrectionTool.NONE,
                        marker = firstWallPoint ?: selectedEndpoint?.point,
                        onPlanTap = { point ->
                            when (tool) {
                                CorrectionTool.NONE -> Unit
                                CorrectionTool.ADD_WALL -> {
                                    val start = firstWallPoint
                                    if (start == null) {
                                        firstWallPoint = point
                                        correctionMessage = "حدد الآن نهاية الجدار الناقص."
                                    } else {
                                        submit(
                                            GeometryCorrection.AddWall(
                                                start = start,
                                                end = point,
                                                thicknessMeters = inferredWallThickness(item.plan),
                                            ),
                                            "تمت إضافة الجدار وإعادة قياس المطابقة على الصورة الأصلية.",
                                        )
                                    }
                                }
                                CorrectionTool.DELETE_WALL -> {
                                    val wallIndex = nearestWallIndex(item.plan, point)
                                    if (wallIndex == null) {
                                        correctionMessage = "المس الجدار الزائد نفسه بدقة أكبر."
                                    } else {
                                        submit(
                                            GeometryCorrection.DeleteWall(wallIndex),
                                            "تم حذف الجدار المحدد وإعادة قياس المطابقة.",
                                        )
                                    }
                                }
                                CorrectionTool.MOVE_ENDPOINT -> {
                                    val selected = selectedEndpoint
                                    if (selected == null) {
                                        val nearest = nearestEndpoint(item.plan, point)
                                        if (nearest == null) {
                                            correctionMessage = "المس نهاية جدار قريبة أولاً."
                                        } else {
                                            selectedEndpoint = nearest
                                            correctionMessage = "حدد الآن الموضع الصحيح لهذه النهاية."
                                        }
                                    } else {
                                        submit(
                                            GeometryCorrection.MoveEndpoint(
                                                wallIndex = selected.wallIndex,
                                                endpoint = selected.endpoint,
                                                target = point,
                                            ),
                                            "تم تحريك نهاية الجدار وإعادة قياس المطابقة.",
                                        )
                                    }
                                }
                            }
                        },
                    )

                    Text(
                        text = "لون الجدران = حالة المطابقة • أزرق = الأبواب • سماوي = النوافذ. المربعات الوردية تحدد حبر جدران لم تغطه الهندسة، والبنفسجية تحدد هندسة زائدة محتملة.",
                        color = ReviewBlue,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (report.issues.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (missingRegions > 0) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = ReviewMissing.copy(alpha = 0.10f),
                                ) {
                                    Text(
                                        text = "مناطق ناقصة: $missingRegions",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        color = ReviewMissing,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            if (extraRegions > 0) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = ReviewExtra.copy(alpha = 0.10f),
                                ) {
                                    Text(
                                        text = "هندسة زائدة: $extraRegions",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        color = ReviewExtra,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (blocking) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Text(
                            "تصحيح هندسي صريح",
                            color = ReviewInk,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "اختر أداة ثم المس المخطط. كل تعديل يعاد فحصه مقابل الصورة الأصلية؛ لا يوجد زر يفرض PASS.",
                            color = Color(0xFF68635D),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CorrectionToolButton(
                                label = "إضافة جدار",
                                selected = tool == CorrectionTool.ADD_WALL,
                                enabled = !correctionBusy,
                                onClick = {
                                    tool = CorrectionTool.ADD_WALL
                                    firstWallPoint = null
                                    selectedEndpoint = null
                                    correctionMessage = "المس بداية الجدار الناقص ثم نهايته."
                                },
                            )
                            CorrectionToolButton(
                                label = "حذف جدار زائد",
                                selected = tool == CorrectionTool.DELETE_WALL,
                                enabled = !correctionBusy,
                                onClick = {
                                    tool = CorrectionTool.DELETE_WALL
                                    firstWallPoint = null
                                    selectedEndpoint = null
                                    correctionMessage = "المس الجدار الزائد الذي تريد حذفه."
                                },
                            )
                            CorrectionToolButton(
                                label = "تحريك نهاية",
                                selected = tool == CorrectionTool.MOVE_ENDPOINT,
                                enabled = !correctionBusy,
                                onClick = {
                                    tool = CorrectionTool.MOVE_ENDPOINT
                                    firstWallPoint = null
                                    selectedEndpoint = null
                                    correctionMessage = "المس نهاية الجدار ثم موضعها الصحيح."
                                },
                            )
                        }
                        correctionMessage?.let {
                            Text(
                                text = it,
                                color = if (report.status == GeometryFidelityStatus.PASS) ReviewPass else ReviewBlue,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (correctionCount > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (!correctionBusy) {
                                            correctionBusy = true
                                            scope.launch {
                                                GeometryReviewStore.undoLastCorrection(item.floorIndex)
                                                correctionBusy = false
                                                firstWallPoint = null
                                                selectedEndpoint = null
                                                correctionMessage = "تم التراجع عن آخر تصحيح."
                                            }
                                        }
                                    },
                                    enabled = !correctionBusy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("تراجع ($correctionCount)")
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (!correctionBusy) {
                                            correctionBusy = true
                                            scope.launch {
                                                GeometryReviewStore.clearCorrections(item.floorIndex)
                                                correctionBusy = false
                                                firstWallPoint = null
                                                selectedEndpoint = null
                                                correctionMessage = "تم حذف جميع التصحيحات لهذا الدور."
                                            }
                                        }
                                    },
                                    enabled = !correctionBusy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("إلغاء التصحيحات")
                                }
                            }
                        }
                        if (report.status == GeometryFidelityStatus.PASS) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = ReviewPass.copy(alpha = 0.10f),
                            ) {
                                Text(
                                    text = "هذا الدور اجتاز بوابة المطابقة بعد التصحيح. أغلق المراجعة واضغط تنفيذ؛ سيعاد تطبيق تصحيحاتك على استخراج جديد ثم يكمل بناء 3D فقط إذا ظل PASS.",
                                    modifier = Modifier.padding(10.dp),
                                    color = ReviewPass,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
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
                    text = "قاعدة منزل: التصحيح يغيّر الهندسة فقط بإشارة منك، وبعدها يعاد القياس مقابل المصدر. REVIEW/BLOCKED لا يتحول إلى PASS شكلياً.",
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
private fun GeometryCorrectionImage(
    item: GeometryReviewItem,
    enabled: Boolean,
    marker: Vec2?,
    onPlanTap: (Vec2) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp, max = 430.dp)
            .onSizeChanged { size = it }
            .pointerInput(enabled, item.floorIndex, item.plan) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    containerToPlan(offset, size, item)?.let(onPlanTap)
                }
            },
    ) {
        Image(
            bitmap = item.overlay.asImageBitmap(),
            contentDescription = "تطابق الهندسة مع ${reviewFloorNameArabic(item.floorIndex)}",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        if (marker != null && size.width > 0 && size.height > 0) {
            val markerOffset = planToContainer(marker, size, item)
            if (markerOffset != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = ReviewBlue,
                        radius = 8.dp.toPx(),
                        center = markerOffset,
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3.dp.toPx(),
                        center = markerOffset,
                    )
                }
            }
        }
    }
}

@Composable
private fun CorrectionToolButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
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

private fun containerToPlan(offset: Offset, size: IntSize, item: GeometryReviewItem): Vec2? {
    val fitted = fittedImageRect(size, item.overlay.width, item.overlay.height) ?: return null
    if (offset.x < fitted.left || offset.x > fitted.right || offset.y < fitted.top || offset.y > fitted.bottom) return null
    val fx = ((offset.x - fitted.left) / fitted.width).coerceIn(0f, 1f)
    val fy = ((offset.y - fitted.top) / fitted.height).coerceIn(0f, 1f)
    val transform = PlanRasterTransform.forImage(item.plan, item.overlay.width, item.overlay.height)
    return transform.imageToPlan(fx * item.overlay.width, fy * item.overlay.height)
}

private fun planToContainer(point: Vec2, size: IntSize, item: GeometryReviewItem): Offset? {
    val fitted = fittedImageRect(size, item.overlay.width, item.overlay.height) ?: return null
    val transform = PlanRasterTransform.forImage(item.plan, item.overlay.width, item.overlay.height)
    val (px, py) = transform.planToImage(point)
    val fx = px / item.overlay.width.toFloat()
    val fy = py / item.overlay.height.toFloat()
    return Offset(
        x = fitted.left + fx * fitted.width,
        y = fitted.top + fy * fitted.height,
    )
}

private data class FittedRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

private fun fittedImageRect(size: IntSize, imageWidth: Int, imageHeight: Int): FittedRect? {
    if (size.width <= 0 || size.height <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
    val containerRatio = size.width / size.height.toFloat()
    val imageRatio = imageWidth / imageHeight.toFloat()
    return if (containerRatio > imageRatio) {
        val height = size.height.toFloat()
        val width = height * imageRatio
        FittedRect((size.width - width) * 0.5f, 0f, width, height)
    } else {
        val width = size.width.toFloat()
        val height = width / imageRatio
        FittedRect(0f, (size.height - height) * 0.5f, width, height)
    }
}

private fun nearestWallIndex(plan: FloorPlan, point: Vec2): Int? {
    var bestIndex = -1
    var bestDistance = Float.MAX_VALUE
    plan.walls.forEachIndexed { index, wall ->
        val distance = pointSegmentDistance(point, wall.start, wall.end)
        val threshold = max(0.28f, wall.thicknessMeters * 0.5f + 0.18f)
        if (distance <= threshold && distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return bestIndex.takeIf { it >= 0 }
}

private fun nearestEndpoint(plan: FloorPlan, point: Vec2): SelectedEndpoint? {
    var best: SelectedEndpoint? = null
    var bestDistance = MAX_ENDPOINT_PICK_METERS
    plan.walls.forEachIndexed { index, wall ->
        val startDistance = distance(point, wall.start)
        if (startDistance <= bestDistance) {
            bestDistance = startDistance
            best = SelectedEndpoint(index, WallEndpoint.START, wall.start)
        }
        val endDistance = distance(point, wall.end)
        if (endDistance <= bestDistance) {
            bestDistance = endDistance
            best = SelectedEndpoint(index, WallEndpoint.END, wall.end)
        }
    }
    return best
}

private fun inferredWallThickness(plan: FloorPlan): Float {
    val values = plan.walls
        .map { it.thicknessMeters }
        .filter { it.isFinite() && it in 0.07f..0.60f }
        .sorted()
    if (values.isEmpty()) return 0.18f
    val middle = values.size / 2
    return if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) * 0.5f
}

private fun pointSegmentDistance(point: Vec2, a: Vec2, b: Vec2): Float {
    val vx = b.x - a.x
    val vz = b.z - a.z
    val lengthSq = vx * vx + vz * vz
    if (lengthSq <= 1e-6f) return distance(point, a)
    val t = (((point.x - a.x) * vx + (point.z - a.z) * vz) / lengthSq).coerceIn(0f, 1f)
    return distance(point, Vec2(a.x + vx * t, a.z + vz * t))
}

private fun distance(a: Vec2, b: Vec2): Float {
    val dx = a.x - b.x
    val dz = a.z - b.z
    return sqrt(dx * dx + dz * dz)
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
    if (report.issues.isNotEmpty()) {
        advice += "تم تحديد ${report.issues.size} مناطق محلية ذات أكبر اختلاف لتوجيه المراجعة بدلاً من البحث في كامل المخطط."
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

private const val MAX_ENDPOINT_PICK_METERS = 0.55f
