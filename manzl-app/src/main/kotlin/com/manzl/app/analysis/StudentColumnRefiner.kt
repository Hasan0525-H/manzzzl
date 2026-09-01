package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.StructuralColumn
import com.manzl.app.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Column ensemble refiner: deterministic OpenCV first, distilled-student proposals second.
 *
 * Both paths are source-raster bound. OpenCV can recover clean compact column symbols even when the
 * student asset is absent; student proposals are promoted only after a second oriented border/interior
 * ink verification and deduplication against the deterministic result. No style prior creates columns.
 */
internal object StudentColumnRefiner {

    data class Result(
        val plan: FloorPlan,
        val proposedCount: Int,
        val acceptedCount: Int,
    )

    fun refine(source: Bitmap, seed: FloorPlan): Result {
        if (source.width <= 16 || source.height <= 16) return Result(seed, 0, 0)

        val deterministic = OpenCvColumnExpert.refine(source, seed)
        val base = deterministic.plan
        val components = StudentSemanticEvidenceStore.get(source)
            .filter { component ->
                component.classId == StudentSemanticComponentDecoder.COLUMN_CLASS_ID &&
                    component.confidence >= MIN_STUDENT_COLUMN_CONFIDENCE &&
                    !component.touchesModelEdge
            }
        if (components.isEmpty()) {
            return Result(base, deterministic.proposedCount, deterministic.acceptedCount)
        }

        val sourceTransform = PlanRasterTransform.forImage(base, source.width, source.height)
        val candidates = components.mapNotNull { component ->
            componentToColumn(component, sourceTransform)
        }.sortedByDescending { it.confidence }
        if (candidates.isEmpty()) {
            return Result(base, deterministic.proposedCount + components.size, deterministic.acceptedCount)
        }

        val working = source.downscaleForColumnAnalysis(MAX_ANALYSIS_SIDE)
        return try {
            val raster = StructuralRasterMask.classify(working)
            val workingTransform = PlanRasterTransform.forImage(base, working.width, working.height)
            val accepted = ArrayList(base.columns)
            var neuralAccepted = 0

            for (candidate in candidates) {
                if (accepted.any { existing -> duplicate(existing, candidate) }) continue
                val support = measureSupport(
                    mask = raster.mask,
                    width = working.width,
                    height = working.height,
                    transform = workingTransform,
                    column = candidate,
                )
                if (!support.accepted) continue

                val verified = candidate.copy(
                    confidence = (
                        candidate.confidence * STUDENT_WEIGHT +
                            support.confidence * RASTER_WEIGHT
                        ).coerceIn(MIN_ACCEPTED_COLUMN_CONFIDENCE, MAX_ACCEPTED_COLUMN_CONFIDENCE),
                )
                accepted += verified
                neuralAccepted++
                if (accepted.size >= MAX_COLUMNS_PER_FLOOR) break
            }

            val totalAccepted = deterministic.acceptedCount + neuralAccepted
            Result(
                plan = if (neuralAccepted == 0) base else base.copy(columns = accepted),
                proposedCount = deterministic.proposedCount + candidates.size,
                acceptedCount = totalAccepted,
            )
        } finally {
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    private fun componentToColumn(
        component: StudentSemanticComponentDecoder.Component,
        transform: PlanRasterTransform,
    ): StructuralColumn? {
        val radians = component.rotationDegrees * PI.toFloat() / 180f
        val ux = cos(radians)
        val uy = sin(radians)
        val nx = -uy
        val ny = ux
        val majorHalf = component.majorSpanPx * 0.5f
        val minorHalf = component.minorSpanPx * 0.5f

        val center = transform.imageToPlan(component.centerX, component.centerY)
        val majorA = transform.imageToPlan(
            component.centerX - ux * majorHalf,
            component.centerY - uy * majorHalf,
        )
        val majorB = transform.imageToPlan(
            component.centerX + ux * majorHalf,
            component.centerY + uy * majorHalf,
        )
        val minorA = transform.imageToPlan(
            component.centerX - nx * minorHalf,
            component.centerY - ny * minorHalf,
        )
        val minorB = transform.imageToPlan(
            component.centerX + nx * minorHalf,
            component.centerY + ny * minorHalf,
        )

        val major = distance(majorA, majorB)
        val minor = distance(minorA, minorB)
        if (major !in MIN_COLUMN_DIMENSION_METERS..MAX_COLUMN_DIMENSION_METERS) return null
        if (minor !in MIN_COLUMN_DIMENSION_METERS..MAX_COLUMN_DIMENSION_METERS) return null
        val aspect = max(major, minor) / min(major, minor).coerceAtLeast(0.001f)
        if (aspect > MAX_COLUMN_ASPECT_RATIO) return null
        val area = major * minor
        if (area !in MIN_COLUMN_AREA_SQ_METERS..MAX_COLUMN_AREA_SQ_METERS) return null

        val dx = majorB.x - majorA.x
        val dz = majorB.z - majorA.z
        var planRotation = Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())).toFloat() % 180f
        if (planRotation < 0f) planRotation += 180f

        return StructuralColumn(
            center = center,
            widthMeters = major,
            depthMeters = minor,
            rotationDegrees = planRotation,
            confidence = component.confidence.coerceIn(0f, 0.96f),
        )
    }

    private fun measureSupport(
        mask: BooleanArray,
        width: Int,
        height: Int,
        transform: PlanRasterTransform,
        column: StructuralColumn,
    ): Support {
        if (mask.size != width * height) return Support(false, 0f)
        val radians = column.rotationDegrees * PI.toFloat() / 180f
        val ux = cos(radians)
        val uz = sin(radians)
        val nx = -uz
        val nz = ux
        val halfW = column.widthMeters * 0.5f
        val halfD = column.depthMeters * 0.5f
        val radiusPx = max(
            MIN_INK_SEARCH_RADIUS_PX,
            (min(transform.pixelsPerMeterX, transform.pixelsPerMeterZ) * INK_SEARCH_RADIUS_METERS).toInt(),
        ).coerceAtMost(MAX_INK_SEARCH_RADIUS_PX)

        var borderTotal = 0
        var borderSupported = 0
        var interiorTotal = 0
        var interiorSupported = 0

        for (iy in 0 until SAMPLE_GRID) {
            val v = -1f + 2f * iy / (SAMPLE_GRID - 1f)
            for (ix in 0 until SAMPLE_GRID) {
                val u = -1f + 2f * ix / (SAMPLE_GRID - 1f)
                val point = Vec2(
                    x = column.center.x + ux * (u * halfW) + nx * (v * halfD),
                    z = column.center.z + uz * (u * halfW) + nz * (v * halfD),
                )
                val (px, py) = transform.planToImage(point)
                val supported = hasInkNear(
                    mask = mask,
                    width = width,
                    height = height,
                    cx = px.toInt(),
                    cy = py.toInt(),
                    radius = radiusPx,
                )
                val border = abs(u) >= BORDER_SAMPLE_THRESHOLD || abs(v) >= BORDER_SAMPLE_THRESHOLD
                if (border) {
                    borderTotal++
                    if (supported) borderSupported++
                } else {
                    interiorTotal++
                    if (supported) interiorSupported++
                }
            }
        }

        val borderRatio = if (borderTotal == 0) 0f else borderSupported / borderTotal.toFloat()
        val interiorRatio = if (interiorTotal == 0) 0f else interiorSupported / interiorTotal.toFloat()
        val accepted = borderRatio >= MIN_BORDER_SUPPORT ||
            (interiorRatio >= MIN_INTERIOR_SUPPORT && borderRatio >= MIN_FILLED_BORDER_SUPPORT)
        val confidence = max(
            borderRatio,
            interiorRatio * INTERIOR_CONFIDENCE_FACTOR + borderRatio * BORDER_CONFIDENCE_FACTOR,
        ).coerceIn(0f, 1f)
        return Support(accepted, confidence)
    }

    private fun hasInkNear(
        mask: BooleanArray,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        radius: Int,
    ): Boolean {
        if (cx !in 0 until width || cy !in 0 until height) return false
        val rSq = radius * radius
        for (dy in -radius..radius) {
            val y = cy + dy
            if (y !in 0 until height) continue
            val row = y * width
            for (dx in -radius..radius) {
                if (dx * dx + dy * dy > rSq) continue
                val x = cx + dx
                if (x in 0 until width && mask[row + x]) return true
            }
        }
        return false
    }

    private fun duplicate(a: StructuralColumn, b: StructuralColumn): Boolean {
        val centerDistance = distance(a.center, b.center)
        if (centerDistance > DUPLICATE_CENTER_METERS) return false
        val aArea = a.widthMeters * a.depthMeters
        val bArea = b.widthMeters * b.depthMeters
        val ratio = min(aArea, bArea) / max(aArea, bArea).coerceAtLeast(0.001f)
        return ratio >= DUPLICATE_MIN_AREA_RATIO
    }

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun Bitmap.downscaleForColumnAnalysis(maxSide: Int): Bitmap {
        val longest = max(width, height)
        if (longest <= maxSide) return this
        val ratio = maxSide / longest.toFloat()
        return Bitmap.createScaledBitmap(
            this,
            max(1, (width * ratio).toInt()),
            max(1, (height * ratio).toInt()),
            true,
        )
    }

    private data class Support(val accepted: Boolean, val confidence: Float)

    private const val MAX_ANALYSIS_SIDE = 2400
    private const val MIN_STUDENT_COLUMN_CONFIDENCE = 0.72f
    private const val MIN_COLUMN_DIMENSION_METERS = 0.16f
    private const val MAX_COLUMN_DIMENSION_METERS = 1.40f
    private const val MAX_COLUMN_ASPECT_RATIO = 2.8f
    private const val MIN_COLUMN_AREA_SQ_METERS = 0.035f
    private const val MAX_COLUMN_AREA_SQ_METERS = 1.60f
    private const val MAX_COLUMNS_PER_FLOOR = 64
    private const val SAMPLE_GRID = 9
    private const val BORDER_SAMPLE_THRESHOLD = 0.72f
    private const val INK_SEARCH_RADIUS_METERS = 0.018f
    private const val MIN_INK_SEARCH_RADIUS_PX = 1
    private const val MAX_INK_SEARCH_RADIUS_PX = 4
    private const val MIN_BORDER_SUPPORT = 0.58f
    private const val MIN_INTERIOR_SUPPORT = 0.50f
    private const val MIN_FILLED_BORDER_SUPPORT = 0.36f
    private const val INTERIOR_CONFIDENCE_FACTOR = 0.58f
    private const val BORDER_CONFIDENCE_FACTOR = 0.42f
    private const val STUDENT_WEIGHT = 0.42f
    private const val RASTER_WEIGHT = 0.58f
    private const val MIN_ACCEPTED_COLUMN_CONFIDENCE = 0.74f
    private const val MAX_ACCEPTED_COLUMN_CONFIDENCE = 0.97f
    private const val DUPLICATE_CENTER_METERS = 0.24f
    private const val DUPLICATE_MIN_AREA_RATIO = 0.62f
}
