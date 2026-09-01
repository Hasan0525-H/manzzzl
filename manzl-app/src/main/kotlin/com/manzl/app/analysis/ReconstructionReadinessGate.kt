package com.manzl.app.analysis

import com.manzl.app.model.DoorEvidenceKind
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.VerticalVoidRoomPolicy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Final fail-closed gate between reconstructed 2D topology and user-visible 3D. */
internal object ReconstructionReadinessGate {

    data class Report(
        val unresolvedOpenings: List<MeasuredOpeningGapDetector.Gap>,
        val unsupportedVerticalVoids: List<RoomRegion>,
        val trustedRoomCoverage: Float,
        val trustedRoomCount: Int,
    ) {
        val ready: Boolean
            get() = unresolvedOpenings.isEmpty() &&
                unsupportedVerticalVoids.isEmpty() &&
                trustedRoomCount > 0 &&
                trustedRoomCoverage >= MIN_TRUSTED_ROOM_COVERAGE
    }

    fun evaluate(plan: FloorPlan): Report {
        val unresolved = MeasuredOpeningGapDetector.detect(
            walls = plan.walls,
            minWidthMeters = MIN_REVIEW_GAP_METERS,
            maxWidthMeters = MAX_REVIEW_GAP_METERS,
            maxResults = MAX_GAP_RESULTS,
        ).filter { gap ->
            gap.supportConfidence >= MIN_GAP_SUPPORT_CONFIDENCE &&
                gap.thicknessAgreement >= MIN_GAP_THICKNESS_AGREEMENT &&
                !hasClassifiedOpening(plan, gap)
        }

        val trustedRooms = plan.rooms.filter(::isTrustedRoom)
        val verticalVoids = trustedRooms.filter(VerticalVoidRoomPolicy::isVerticalVoid)
        val surfaceRooms = trustedRooms.filterNot(VerticalVoidRoomPolicy::isVerticalVoid)
        val unsupportedVoids = verticalVoids.filter { void ->
            // The current mesher can preserve a shaft when it is an independent closed planar face:
            // simply omit that face from floor/ceiling generation. If its centroid still lies inside
            // another trusted surface polygon, omission would not create a hole because that surface
            // would fill underneath it. Fail closed until polygon subtraction handles that topology.
            val centre = polygonCentroid(void.polygon) ?: return@filter true
            surfaceRooms.any { room -> pointInsidePolygon(centre, room.polygon) }
        }
        val coverage = sampledRoomCoverage(plan, surfaceRooms)
        return Report(
            unresolvedOpenings = unresolved,
            unsupportedVerticalVoids = unsupportedVoids,
            trustedRoomCoverage = coverage,
            trustedRoomCount = surfaceRooms.size,
        )
    }

    fun rejectionMessageArabic(plan: FloorPlan): String? {
        val report = evaluate(plan)
        if (report.ready) return null

        if (report.unresolvedOpenings.isNotEmpty()) {
            val strongest = report.unresolvedOpenings.maxByOrNull { it.supportConfidence }
            val widthCm = ((strongest?.widthMeters ?: 0f) * 100f).toInt().coerceAtLeast(1)
            return "أوقفت تحويل المخطط إلى 3D لأن هناك ${report.unresolvedOpenings.size} فتحة جدار مقاسة لم تُصنّف بثقة كباب أو نافذة. أقوى فتحة بعرض يقارب $widthCm سم. لن أخترع باباً أو أترك فتحة خاطئة."
        }

        if (report.unsupportedVerticalVoids.isNotEmpty()) {
            val label = report.unsupportedVerticalVoids.first().label?.trim().orEmpty()
            val suffix = if (label.isBlank()) "" else " ($label)"
            return "أوقفت تحويل المخطط إلى 3D لأن فراغاً رأسياً موثوقاً$suffix متداخل مع سطح غرفة أخرى؛ حذف وجه الشافت وحده لن يصنع فتحة حقيقية في البلاطة. لن أملأه بأرضية وهمية قبل حل التقاطع هندسياً."
        }

        val coverage = (report.trustedRoomCoverage * 100f).toInt().coerceIn(0, 100)
        return if (report.trustedRoomCount == 0) {
            "أوقفت تحويل المخطط إلى 3D لأن حدود الغرف المغلقة لم تُستخرج بثقة كافية. إنشاء أرضية مستطيلة افتراضية سيغيّر شكل المنزل الحقيقي."
        } else {
            "أوقفت تحويل المخطط إلى 3D لأن الغرف الموثوقة تغطي $coverage% فقط من الغلاف المقاس للجدران. لن أبني أرضيات أو أسقف مع مناطق كبيرة مفقودة؛ يجب إغلاق topology أولاً."
        }
    }

    fun planForReview(plan: FloorPlan): FloorPlan {
        val report = evaluate(plan)
        if (report.ready) return plan
        return plan.copy(
            geometryFidelity = plan.geometryFidelity.copy(
                status = com.manzl.app.model.GeometryFidelityStatus.REVIEW_REQUIRED,
            )
        )
    }

    private fun hasClassifiedOpening(plan: FloorPlan, gap: MeasuredOpeningGapDetector.Gap): Boolean {
        val doorMatch = plan.doors.any { door ->
            door.evidenceKind != DoorEvidenceKind.MEASURED_GAP &&
                openingMatches(gap, door.center, door.widthMeters, door.rotationDegrees)
        }
        if (doorMatch) return true
        return plan.windows.any { window ->
            openingMatches(gap, window.center, window.widthMeters, window.rotationDegrees)
        }
    }

    private fun openingMatches(
        gap: MeasuredOpeningGapDetector.Gap,
        center: Vec2,
        widthMeters: Float,
        rotationDegrees: Float,
    ): Boolean {
        val dx = center.x - gap.center.x
        val dz = center.z - gap.center.z
        val centerDistance = sqrt(dx * dx + dz * dz)
        val centerTolerance = max(
            MIN_OPENING_CENTER_TOLERANCE_METERS,
            min(gap.widthMeters, widthMeters) * OPENING_CENTER_TOLERANCE_RATIO,
        )
        if (centerDistance > centerTolerance) return false
        if (axisAngleDifference(gap.rotationDegrees, rotationDegrees) > MAX_OPENING_AXIS_DELTA_DEGREES) return false
        val widthTolerance = max(MIN_OPENING_WIDTH_TOLERANCE_METERS, gap.widthMeters * OPENING_WIDTH_TOLERANCE_RATIO)
        return abs(gap.widthMeters - widthMeters) <= widthTolerance
    }

    private fun isTrustedRoom(room: RoomRegion): Boolean =
        room.confidence >= MIN_TRUSTED_ROOM_CONFIDENCE &&
            room.polygon.size >= 3 &&
            polygonArea(room.polygon) >= MIN_TRUSTED_ROOM_AREA_SQ_METERS

    /**
     * Coverage is measured against the physical wall envelope, not FloorPlan.width×depth. The latter
     * includes conservative content padding and can make a complete reconstruction look sparse (or a
     * partial one look acceptable after a bad crop). Using measured wall faces makes the gate follow
     * the actual building evidence. A deliberately high threshold then blocks houses with large
     * missing floor/ceiling regions instead of relying on the renderer's old 32% fallback boundary.
     */
    private fun sampledRoomCoverage(plan: FloorPlan, rooms: List<RoomRegion>): Float {
        if (rooms.isEmpty()) return 0f
        val envelope = structuralEnvelope(plan) ?: return 0f
        if (envelope.width <= EPSILON || envelope.depth <= EPSILON) return 0f

        var inside = 0
        val total = COVERAGE_GRID * COVERAGE_GRID
        for (zIndex in 0 until COVERAGE_GRID) {
            val z = envelope.minZ + envelope.depth * ((zIndex + 0.5f) / COVERAGE_GRID.toFloat())
            for (xIndex in 0 until COVERAGE_GRID) {
                val x = envelope.minX + envelope.width * ((xIndex + 0.5f) / COVERAGE_GRID.toFloat())
                val point = Vec2(x, z)
                if (rooms.any { room -> pointInsidePolygon(point, room.polygon) }) inside++
            }
        }
        return inside / total.toFloat()
    }

    private fun structuralEnvelope(plan: FloorPlan): Envelope? {
        if (plan.walls.isEmpty()) return null
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (wall in plan.walls) {
            val half = wall.thicknessMeters.coerceIn(MIN_WALL_THICKNESS_METERS, MAX_WALL_THICKNESS_METERS) * 0.5f
            minX = min(minX, min(wall.start.x, wall.end.x) - half)
            maxX = max(maxX, max(wall.start.x, wall.end.x) + half)
            minZ = min(minZ, min(wall.start.z, wall.end.z) - half)
            maxZ = max(maxZ, max(wall.start.z, wall.end.z) + half)
        }
        if (!minX.isFinite() || !maxX.isFinite() || !minZ.isFinite() || !maxZ.isFinite()) return null

        // Never let a rogue wall enlarge the denominator outside the already calibrated plan bounds.
        val planMinX = -plan.widthMeters * 0.5f
        val planMaxX = plan.widthMeters * 0.5f
        val planMinZ = -plan.depthMeters * 0.5f
        val planMaxZ = plan.depthMeters * 0.5f
        val clampedMinX = minX.coerceIn(planMinX, planMaxX)
        val clampedMaxX = maxX.coerceIn(planMinX, planMaxX)
        val clampedMinZ = minZ.coerceIn(planMinZ, planMaxZ)
        val clampedMaxZ = maxZ.coerceIn(planMinZ, planMaxZ)
        if (clampedMaxX - clampedMinX <= EPSILON || clampedMaxZ - clampedMinZ <= EPSILON) return null
        return Envelope(clampedMinX, clampedMaxX, clampedMinZ, clampedMaxZ)
    }

    private fun polygonCentroid(points: List<Vec2>): Vec2? {
        if (points.isEmpty()) return null
        return Vec2(
            x = points.sumOf { it.x.toDouble() }.toFloat() / points.size,
            z = points.sumOf { it.z.toDouble() }.toFloat() / points.size,
        )
    }

    private fun pointInsidePolygon(point: Vec2, polygon: List<Vec2>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val crosses = (current.z > point.z) != (previous.z > point.z)
            if (crosses) {
                val denominator = previous.z - current.z
                val safe = if (abs(denominator) < EPSILON) EPSILON else denominator
                val boundaryX = (previous.x - current.x) * (point.z - current.z) / safe + current.x
                if (point.x < boundaryX) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun polygonArea(points: List<Vec2>): Float {
        var sum = 0f
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            sum += a.x * b.z - b.x * a.z
        }
        return abs(sum) * 0.5f
    }

    private fun axisAngleDifference(a: Float, b: Float): Float {
        val na = normalizeHalfTurn(a)
        val nb = normalizeHalfTurn(b)
        val delta = abs(na - nb)
        return min(delta, 180f - delta)
    }

    private fun normalizeHalfTurn(value: Float): Float {
        var result = value % 180f
        if (result < 0f) result += 180f
        return result
    }

    private data class Envelope(
        val minX: Float,
        val maxX: Float,
        val minZ: Float,
        val maxZ: Float,
    ) {
        val width: Float get() = maxX - minX
        val depth: Float get() = maxZ - minZ
    }

    private const val MIN_REVIEW_GAP_METERS = 0.42f
    private const val MAX_REVIEW_GAP_METERS = 4.20f
    private const val MAX_GAP_RESULTS = 96
    private const val MIN_GAP_SUPPORT_CONFIDENCE = 0.70f
    private const val MIN_GAP_THICKNESS_AGREEMENT = 0.62f
    private const val MIN_OPENING_CENTER_TOLERANCE_METERS = 0.24f
    private const val OPENING_CENTER_TOLERANCE_RATIO = 0.24f
    private const val MIN_OPENING_WIDTH_TOLERANCE_METERS = 0.30f
    private const val OPENING_WIDTH_TOLERANCE_RATIO = 0.24f
    private const val MAX_OPENING_AXIS_DELTA_DEGREES = 12f
    private const val MIN_TRUSTED_ROOM_CONFIDENCE = 0.66f
    private const val MIN_TRUSTED_ROOM_AREA_SQ_METERS = 1.2f
    private const val MIN_TRUSTED_ROOM_COVERAGE = 0.68f
    private const val MIN_WALL_THICKNESS_METERS = 0.06f
    private const val MAX_WALL_THICKNESS_METERS = 0.60f
    private const val COVERAGE_GRID = 64
    private const val EPSILON = 0.000001f
}
