package com.manzl.app.analysis

import android.graphics.Bitmap
import android.graphics.Color
import com.manzl.app.model.AnalysisUpdate
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Offline structural analyzer.
 *
 * Deterministic geometry remains the topology authority. The accepted wall axes first establish a
 * structural content envelope; metric scale and every image↔plan transform are then measured against
 * that envelope rather than the full screenshot/page. White margins and asymmetric crops therefore
 * cannot silently stretch or shift the house. Bundled OCR remains a narrow local AI component for
 * printed dimensions and low-confidence scale remains reviewable rather than fabricated.
 */
class ClassicalFloorPlanAnalyzer : FloorPlanAnalyzer {

    override suspend fun analyze(bitmap: Bitmap, progress: ProgressSink): FloorPlan =
        withContext(Dispatchers.Default) {
            progress.onUpdate(AnalysisUpdate(4, "تهيئة المخطط"))
            val working = bitmap.downscaleForAnalysis(MAX_SIDE)
            val width = working.width
            val height = working.height
            require(width > 32 && height > 32) { "الصورة صغيرة جداً للتحليل" }

            val pixels = IntArray(width * height)
            working.getPixels(pixels, 0, width, 0, 0, width, height)
            coroutineContext.ensureActive()

            progress.onUpdate(AnalysisUpdate(14, "فصل الجدران عن النصوص والرموز"))
            var blueStructuralCount = 0
            for (color in pixels) {
                if (isBlueStructural(color)) blueStructuralCount++
            }
            val preferBlue = blueStructuralCount >= (pixels.size * BLUE_MODE_MIN_RATIO).toInt()

            val mask = BooleanArray(pixels.size)
            for (index in pixels.indices) {
                val color = pixels[index]
                mask[index] = if (preferBlue) {
                    isBlueStructural(color)
                } else {
                    isMonochromeStructural(color)
                }
            }
            coroutineContext.ensureActive()

            progress.onUpdate(AnalysisUpdate(28, "اكتشاف محاور الجدران"))
            val horizontalRaw = scanHorizontal(mask, width, height)
            val verticalRaw = scanVertical(mask, width, height)

            progress.onUpdate(AnalysisUpdate(43, "دمج سماكات الجدران وتنظيف الضوضاء"))
            val mergeDistance = max(3, min(width, height) / 180)
            val horizontal = mergeParallel(horizontalRaw, mergeDistance)
            val vertical = mergeParallel(verticalRaw, mergeDistance)

            val structuralPoints = buildList {
                horizontal.forEach { segment ->
                    add(segment.from to segment.fixed)
                    add(segment.to to segment.fixed)
                }
                vertical.forEach { segment ->
                    add(segment.fixed to segment.from)
                    add(segment.fixed to segment.to)
                }
            }
            val contentBounds = StructuralContentBounds.fromPoints(
                imageWidth = width,
                imageHeight = height,
                points = structuralPoints,
            )
            val normalizedBounds = contentBounds.normalized(width, height)

            coroutineContext.ensureActive()
            progress.onUpdate(AnalysisUpdate(52, "تحديد حدود الرسم الفعلية وإزالة تأثير الهوامش"))

            progress.onUpdate(AnalysisUpdate(58, "قراءة الأبعاد المطبوعة بالذكاء الاصطناعي المحلي"))
            val calibration = MetricScaleCalibrator.calibrate(working, contentBounds)

            coroutineContext.ensureActive()
            progress.onUpdate(AnalysisUpdate(67, "تحويل الرسم إلى هندسة مترية"))

            val pxToMeter = calibration.longSideMeters /
                max(contentBounds.width, contentBounds.height).toFloat()
            val planWidth = contentBounds.width * pxToMeter
            val planDepth = contentBounds.height * pxToMeter

            val rawWalls = buildList {
                horizontal.forEach { segment ->
                    add(
                        WallSegment(
                            start = Vec2(
                                x = segment.from.toCenteredX(contentBounds, pxToMeter),
                                z = segment.fixed.toCenteredZ(contentBounds, pxToMeter),
                            ),
                            end = Vec2(
                                x = segment.to.toCenteredX(contentBounds, pxToMeter),
                                z = segment.fixed.toCenteredZ(contentBounds, pxToMeter),
                            ),
                            confidence = if (preferBlue) 0.92f else 0.72f,
                        )
                    )
                }
                vertical.forEach { segment ->
                    add(
                        WallSegment(
                            start = Vec2(
                                x = segment.fixed.toCenteredX(contentBounds, pxToMeter),
                                z = segment.from.toCenteredZ(contentBounds, pxToMeter),
                            ),
                            end = Vec2(
                                x = segment.fixed.toCenteredX(contentBounds, pxToMeter),
                                z = segment.to.toCenteredZ(contentBounds, pxToMeter),
                            ),
                            confidence = if (preferBlue) 0.92f else 0.72f,
                        )
                    )
                }
            }.filter { wall ->
                val dx = wall.end.x - wall.start.x
                val dz = wall.end.z - wall.start.z
                dx * dx + dz * dz >= MIN_WALL_METERS * MIN_WALL_METERS
            }

            progress.onUpdate(AnalysisUpdate(74, "تصحيح تقاطعات الجدران والفجوات الصغيرة"))
            val walls = StructuralTopologyReconciler.reconcile(rawWalls)

            require(walls.size >= 4) {
                "لم أتمكن من استخراج جدران كافية. جرّب صورة أوضح أو قص المخطط فقط."
            }

            progress.onUpdate(AnalysisUpdate(82, "بناء حدود الغرف والممرات"))
            val densityConfidence = (walls.size / 28f).coerceIn(0.45f, 1f)
            val modeConfidence = if (preferBlue) 0.94f else 0.72f
            val geometryConfidence = (densityConfidence * modeConfidence).coerceIn(0f, 0.97f)
            val confidence = (
                geometryConfidence * 0.86f + calibration.confidence.coerceIn(0f, 1f) * 0.14f
                ).coerceIn(0f, 0.97f)

            coroutineContext.ensureActive()
            progress.onUpdate(AnalysisUpdate(90, "استنتاج فتحات الأبواب وربط الغرف"))

            val structural = FloorPlan(
                widthMeters = planWidth,
                depthMeters = planDepth,
                walls = walls,
                analysisConfidence = confidence,
                sourceWidthPx = bitmap.width,
                sourceHeightPx = bitmap.height,
                scaleConfidence = calibration.confidence,
                scaleSource = calibration.source,
                contentLeftFraction = normalizedBounds.left,
                contentTopFraction = normalizedBounds.top,
                contentRightFraction = normalizedBounds.right,
                contentBottomFraction = normalizedBounds.bottom,
            )
            val doors = DoorInferenceEngine.infer(structural)

            coroutineContext.ensureActive()
            progress.onUpdate(AnalysisUpdate(96, "تجهيز المجسم ثلاثي الأبعاد"))

            structural.copy(doors = doors).also {
                progress.onUpdate(AnalysisUpdate(100, "تم تجهيز المنزل للجولة"))
            }
        }

    private fun scanHorizontal(mask: BooleanArray, width: Int, height: Int): List<PixelSegment> {
        val minRun = max(16, width / 55)
        val result = ArrayList<PixelSegment>()
        for (y in 1 until height - 1 step 2) {
            var start = -1
            for (x in 1 until width - 1) {
                val solid = mask[y * width + x] && (
                    mask[(y - 1) * width + x] || mask[(y + 1) * width + x]
                )
                if (solid && start < 0) start = x
                if ((!solid || x == width - 2) && start >= 0) {
                    val end = if (solid) x else x - 1
                    if (end - start + 1 >= minRun) {
                        result += PixelSegment(horizontal = true, fixed = y, from = start, to = end)
                    }
                    start = -1
                }
            }
        }
        return result
    }

    private fun scanVertical(mask: BooleanArray, width: Int, height: Int): List<PixelSegment> {
        val minRun = max(16, height / 55)
        val result = ArrayList<PixelSegment>()
        for (x in 1 until width - 1 step 2) {
            var start = -1
            for (y in 1 until height - 1) {
                val solid = mask[y * width + x] && (
                    mask[y * width + (x - 1)] || mask[y * width + (x + 1)]
                )
                if (solid && start < 0) start = y
                if ((!solid || y == height - 2) && start >= 0) {
                    val end = if (solid) y else y - 1
                    if (end - start + 1 >= minRun) {
                        result += PixelSegment(horizontal = false, fixed = x, from = start, to = end)
                    }
                    start = -1
                }
            }
        }
        return result
    }

    private fun mergeParallel(raw: List<PixelSegment>, mergeDistance: Int): List<PixelSegment> {
        val merged = ArrayList<PixelSegment>()
        for (candidate in raw.sortedWith(compareBy<PixelSegment> { it.fixed }.thenBy { it.from })) {
            val index = merged.indexOfFirst { existing ->
                existing.horizontal == candidate.horizontal &&
                    abs(existing.fixed - candidate.fixed) <= mergeDistance &&
                    overlapRatio(existing, candidate) >= 0.55f
            }
            if (index < 0) {
                merged += candidate
            } else {
                val existing = merged[index]
                merged[index] = existing.copy(
                    fixed = ((existing.fixed + candidate.fixed) / 2f).toInt(),
                    from = min(existing.from, candidate.from),
                    to = max(existing.to, candidate.to),
                )
            }
        }

        val lengths = merged.map { it.to - it.from }.sorted()
        if (lengths.isEmpty()) return emptyList()
        val median = lengths[lengths.size / 2].coerceAtLeast(1)
        val adaptiveMin = max(12, (median * 0.42f).toInt())
        return merged.filter { it.to - it.from >= adaptiveMin }
    }

    private fun overlapRatio(a: PixelSegment, b: PixelSegment): Float {
        val overlap = max(0, min(a.to, b.to) - max(a.from, b.from))
        val shorter = min(a.to - a.from, b.to - b.from).coerceAtLeast(1)
        return overlap.toFloat() / shorter.toFloat()
    }

    private fun isBlueStructural(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        val saturation = maxChannel - minChannel
        return b >= 105 &&
            b > r * 1.18f &&
            b > g * 1.04f &&
            saturation >= 38
    }

    private fun isMonochromeStructural(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luminance = (r * 0.2126f) + (g * 0.7152f) + (b * 0.0722f)
        val chroma = max(r, max(g, b)) - min(r, min(g, b))
        return luminance <= 112f && chroma <= 58
    }

    private fun Bitmap.downscaleForAnalysis(maxSide: Int): Bitmap {
        val longest = max(width, height)
        if (longest <= maxSide) return this
        val ratio = maxSide.toFloat() / longest.toFloat()
        val targetWidth = max(1, (width * ratio).toInt())
        val targetHeight = max(1, (height * ratio).toInt())
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun Int.toCenteredX(bounds: PixelContentBounds, scale: Float): Float =
        (this - (bounds.left + bounds.rightExclusive) * 0.5f) * scale

    private fun Int.toCenteredZ(bounds: PixelContentBounds, scale: Float): Float =
        (this - (bounds.top + bounds.bottomExclusive) * 0.5f) * scale

    private data class PixelSegment(
        val horizontal: Boolean,
        val fixed: Int,
        val from: Int,
        val to: Int,
    )

    companion object {
        private const val MAX_SIDE = 1400
        private const val BLUE_MODE_MIN_RATIO = 0.0014f
        private const val MIN_WALL_METERS = 0.38f
    }
}
