package com.manzl.app.analysis

import com.manzl.app.model.DoorEvidenceKind
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Final reconstruction gate after semantic opening classification and room topology.
 *
 * Geometry fidelity alone can be high while the resulting house is still visibly wrong: an
 * opening-sized gap can remain unclassified and become a floor-to-ceiling hole, or room topology can
 * be too sparse and force a fake rectangular floor slab. This gate blocks those cases before any 3D
 * mesh is exposed to the user.
 *
 * It never invents geometry. It only asks whether the measured geometry has enough evidence to be
 * represented faithfully. Ambiguous cases go back to the 2D review surface.
 */
internal object ReconstructionReadinessGate {

    data class Report(
        val unresolvedOpenings: List<MeasuredOpeningGapDetector.Gap>,
        val trustedRoomCoverage: Float,
        val trustedRoomCount: Int,
    ) {
        val ready: Boolean
            get() = unresolvedOpenings.isEmpty() &&
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
        val coverage = sampledRoomCoverage(plan, trustedRooms)
        return Report(
            unresolvedOpenings = unresolved,
            trustedRoomCoverage = coverage,
            trustedRoomCount = trustedRooms.size,
        )
    }

    fun rejectionMessageArabic(plan: FloorPlan): String? {
        val report = evaluate(plan)
        if (report.ready) return null

        if (report.unresolvedOpenings.isNotEmpty()) {
            val strongest = report.unresolvedOpenings.maxByOrNull { it.supportConfidence }
            val widthCm = ((strongest?.widthMeters ?: 0f) * 100f).toInt().coerceAtLeast(1)
            return "أوقفت تحويل المخطط إلى 3D لأن هناك ${report.unresolvedOpenings.size} فتحة جدار مقاسة لم تُصنّف بثقة كباب أو نافذة. أقوى فتحة بعرض يقارب $widthCm سم. تركها سيُنتج فتحة كاملة في الجدار أو باباً مخترعاً، لذلك لن أعرض منزلاً خاطئاً؛ راجع الفتحات المعلّمة في المخطط."
        }

        val coverage = (report.trustedRoomCoverage * 100f).toInt().coerceIn(0, 100)
        return if (report.trustedRoomCount == 0) {
            "أوقفت تحويل المخطط إلى 3D لأن حدود الغرف المغلقة لم تُستخرج بثقة كافية. إنشاء أرضية مستطيلة افتراضية سيغيّر شكل المنزل الحقيقي، لذلك يلزم تحسين استخراج الجدران/الغرف أو مراجعة المخطط قبل بناء 3D."
        } else {
            "أوقفت تحويل المخطط إلى 3D لأن تغطية الغرف الموثوقة لا تتجاوز $coverage% من نطاق المخطط. هذه التغطية غير كافية لبناء أرضيات وأسقف مطابقة من دون اختراع مساحات؛ راجع المناطق غير المغلقة أولاً."
        }
    }

    /** Returns a diagnostic copy that the existing review UI can show as review-required. */
    fun planForReview(plan: FloorPlan): FloorPlan {
        val report = evaluate(plan)
        if (report.ready) return plan
        return plan.copy(
            geometryFidelity = plan.geometryFidelity.copy(
                status = com.manzl.app.model.GeometryFidelityStatus.REVIEW_REQUIRED,
            )
        )
    }

    private fun hasClassifiedOpening(
        plan: FloorPlan,
        gap: MeasuredOpeningGapDetector.Gap,
    ): Boolean {
        val doorMatch = plan.doors.any { door ->
            door.evidenceKind != DoorEvidenceKind.MEASURED_GAP &&
                openingMatches(
                    gap = gap,
                    center = door.center,
                    widthMeters = door.widthMeters,
                    rotationDegrees = door.rotationDegrees,
                )
        }
        if (doorMatch) return true
        return plan.windows.any { window ->
            openingMatches(
                gap = gap,
                center = window.center,
                widthMeters = window.widthMeters,
                rotationDegrees = window.rotationDegrees,
            )
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
        if (axisAngleDifference(gap.rotationDegrees, rotationDegrees) > MAX_OPENING_AXIS_DELTA_DEGREES) {
            return false
        }
        val widthTolerance = max(MIN_OPENING_WIDTH_TOLERANCE_METERS, gap.widthMeters * OPENING_WIDTH_TOLERANCE_RATIO)
        return abs(gap.widthMeters - widthMeters) <= widthTolerance
    }

    private fun isTrustedRoom(room: RoomRegion): Boolean =
        room.confidence >= MIN_TRUSTED_ROOM_CONFIDENCE &&
            room.polygon.size >= 3 &&
            polygonArea(room.polygon) >= MIN_TRUSTED_ROOM_AREA_SQ_METERS

    /**
     * Approximate polygon-union coverage on a fixed grid. This avoids double-counting overlapping
     * room hypotheses and is deterministic/cheap enough for every floor on-device.
     */
    private fun sampledRoomCoverage(plan: FloorPlan, rooms: List<RoomRegion>): Float {
        if (rooms.isEmpty() || plan.widthMeters <= 0f || plan.depthMeters <= 0f) return 0f
        var inside = 0
        val total = COVERAGE_GRID * COVERAGE_GRID
        val minX = -plan.widthMeters * 0.5f
        val minZ = -plan.depthMeters * 0.5f
        for (zIndex in 0 until COVERAGE_GRID) {
            val z = minZ + plan.depthMeters * ((zIndex + 0.5f) / COVERAGE_GRID.toFloat())
            for (xIndex in 0 until COVERAGE_GRID) {
                val x = minX + plan.widthMeters * ((xIndex + 0.5f) / COVERAGE_GRID.toFloat())
                val point = Vec2(x, z)
                if (rooms.any { room -> pointInsidePolygon(point, room.polygon) }) inside++
            }
        }
        return inside / total.toFloat()
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
    private const val MIN_TRUSTED_ROOM_COVERAGE = 0.18f
    private const val COVERAGE_GRID = 48
    private const val EPSILON = 0.000001f
}
