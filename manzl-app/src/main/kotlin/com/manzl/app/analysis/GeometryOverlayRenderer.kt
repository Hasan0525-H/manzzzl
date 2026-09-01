package com.manzl.app.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityIssueKind
import com.manzl.app.model.GeometryFidelityStatus
import com.manzl.app.model.StructuralColumn
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Renders the extracted geometry directly over the uploaded plan.
 *
 * This is a development/user trust surface, not a decorative preview: if a wall/column is missing,
 * shifted, stops just short of a real junction, leaves an unresolved opening, or a room polygon has
 * an invented unsupported side, the exact problem is visible before the user is allowed into 3D.
 * The source bitmap is never modified.
 */
internal object GeometryOverlayRenderer {

    fun render(source: Bitmap, plan: FloorPlan, maxSide: Int = DEFAULT_MAX_SIDE): Bitmap {
        val longest = max(source.width, source.height).coerceAtLeast(1)
        val scale = if (longest > maxSide) maxSide / longest.toFloat() else 1f
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        val scaled = if (width == source.width && height == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        val output = scaled.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val transform = PlanRasterTransform.forImage(plan, width, height)
        val pixelsPerMeter = (transform.pixelsPerMeterX + transform.pixelsPerMeterZ) * 0.5f

        drawLocalizedIssues(canvas, plan, width, height)

        val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
            color = when (plan.geometryFidelity.status) {
                GeometryFidelityStatus.PASS -> Color.argb(155, 18, 155, 89)
                GeometryFidelityStatus.REVIEW_REQUIRED -> Color.argb(170, 230, 151, 20)
                GeometryFidelityStatus.BLOCKED -> Color.argb(180, 220, 52, 47)
                GeometryFidelityStatus.UNKNOWN -> Color.argb(170, 125, 125, 125)
            }
        }
        val endpointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = wallPaint.color
        }

        plan.walls.forEach { wall ->
            val (ax, ay) = transform.planToImage(wall.start)
            val (bx, by) = transform.planToImage(wall.end)
            wallPaint.strokeWidth = max(MIN_WALL_STROKE_PX, wall.thicknessMeters * pixelsPerMeter)
            canvas.drawLine(ax, ay, bx, by, wallPaint)
            val radius = max(MIN_ENDPOINT_RADIUS_PX, wallPaint.strokeWidth * 0.28f)
            canvas.drawCircle(ax, ay, radius, endpointPaint)
            canvas.drawCircle(bx, by, radius, endpointPaint)
        }

        drawColumns(canvas, plan, transform, pixelsPerMeter)

        // Draw these after walls/columns so the exact crack remains visible instead of being hidden
        // under regular endpoint/structural markers. The marker is diagnostic only and never snaps.
        drawTopologyNearMisses(canvas, plan, transform, pixelsPerMeter)

        val doorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, pixelsPerMeter * 0.035f)
            color = Color.argb(220, 31, 94, 201)
        }
        plan.doors.forEach { door ->
            drawOpeningAxis(
                canvas = canvas,
                transform = transform,
                center = door.center,
                widthMeters = door.widthMeters,
                rotationDegrees = door.rotationDegrees,
                paint = doorPaint,
            )
        }

        val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, pixelsPerMeter * 0.03f)
            color = Color.argb(225, 0, 150, 195)
        }
        plan.windows.forEach { window ->
            drawOpeningAxis(
                canvas = canvas,
                transform = transform,
                center = window.center,
                widthMeters = window.widthMeters,
                rotationDegrees = window.rotationDegrees,
                paint = windowPaint,
            )
        }

        // Final reconstruction diagnostics are drawn last. These are the exact topology reasons that
        // can block 3D even when aggregate wall fidelity is high, so they must never be hidden beneath
        // ordinary green/amber geometry.
        drawReconstructionIssues(canvas, plan, transform, pixelsPerMeter)

        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        return output
    }

    private fun drawColumns(
        canvas: Canvas,
        plan: FloorPlan,
        transform: PlanRasterTransform,
        pixelsPerMeter: Float,
    ) {
        if (plan.columns.isEmpty()) return
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(72, 111, 57, 176)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = max(2.5f, pixelsPerMeter * 0.025f)
            color = Color.argb(235, 92, 42, 158)
        }

        plan.columns
            .filter { it.confidence >= MIN_COLUMN_OVERLAY_CONFIDENCE }
            .forEach { column ->
                val corners = columnCorners(column)
                if (corners.size != 4) return@forEach
                val path = Path()
                val first = transform.planToImage(corners.first())
                path.moveTo(first.first, first.second)
                corners.drop(1).forEach { point ->
                    val pixel = transform.planToImage(point)
                    path.lineTo(pixel.first, pixel.second)
                }
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
    }

    private fun columnCorners(column: StructuralColumn): List<Vec2> {
        if (column.widthMeters <= 0f || column.depthMeters <= 0f) return emptyList()
        val radians = column.rotationDegrees * PI.toFloat() / 180f
        val ux = cos(radians)
        val uz = sin(radians)
        val nx = -uz
        val nz = ux
        val halfW = column.widthMeters * 0.5f
        val halfD = column.depthMeters * 0.5f

        fun point(along: Float, depth: Float) = Vec2(
            x = column.center.x + ux * along + nx * depth,
            z = column.center.z + uz * along + nz * depth,
        )

        return listOf(
            point(-halfW, -halfD),
            point(halfW, -halfD),
            point(halfW, halfD),
            point(-halfW, halfD),
        )
    }

    private fun drawLocalizedIssues(
        canvas: Canvas,
        plan: FloorPlan,
        width: Int,
        height: Int,
    ) {
        if (plan.geometryFidelity.issues.isEmpty()) return
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, minOf(width, height) / 420f)
        }

        plan.geometryFidelity.issues.forEach { issue ->
            val left = issue.leftFraction.coerceIn(0f, 1f) * width
            val top = issue.topFraction.coerceIn(0f, 1f) * height
            val right = issue.rightFraction.coerceIn(0f, 1f) * width
            val bottom = issue.bottomFraction.coerceIn(0f, 1f) * height
            if (right <= left || bottom <= top) return@forEach

            val alpha = (28 + issue.severity.coerceIn(0f, 1f) * 46f).toInt()
            when (issue.kind) {
                GeometryFidelityIssueKind.MISSING_SOURCE -> {
                    fill.color = Color.argb(alpha, 225, 35, 92)
                    border.color = Color.argb(210, 205, 25, 72)
                }
                GeometryFidelityIssueKind.EXTRA_GEOMETRY -> {
                    fill.color = Color.argb(alpha, 112, 58, 196)
                    border.color = Color.argb(205, 88, 43, 171)
                }
            }
            canvas.drawRect(left, top, right, bottom, fill)
            canvas.drawRect(left, top, right, bottom, border)
        }
    }

    private fun drawTopologyNearMisses(
        canvas: Canvas,
        plan: FloorPlan,
        transform: PlanRasterTransform,
        pixelsPerMeter: Float,
    ) {
        val nearMisses = WallTopologyIntegrity.findNearMissJunctions(plan)
        if (nearMisses.isEmpty()) return

        val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = max(2.5f, pixelsPerMeter * 0.035f)
            color = Color.argb(240, 210, 24, 74)
        }
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2.5f, pixelsPerMeter * 0.04f)
            color = Color.argb(245, 210, 24, 74)
        }
        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(245, 255, 219, 65)
        }

        nearMisses.forEach { issue ->
            val target = plan.walls.getOrNull(issue.targetWallIndex) ?: return@forEach
            val projected = projectOntoSegment(issue.endpoint, target)
            val (ex, ey) = transform.planToImage(issue.endpoint)
            val (tx, ty) = transform.planToImage(projected)
            val radius = max(7f, pixelsPerMeter * TOPOLOGY_MARKER_RADIUS_METERS)

            canvas.drawLine(ex, ey, tx, ty, connectorPaint)
            canvas.drawCircle(ex, ey, radius, ringPaint)
            canvas.drawCircle(ex, ey, max(2.5f, radius * 0.24f), centerPaint)
            canvas.drawCircle(tx, ty, max(4f, radius * 0.55f), ringPaint)
        }
    }

    private fun drawReconstructionIssues(
        canvas: Canvas,
        plan: FloorPlan,
        transform: PlanRasterTransform,
        pixelsPerMeter: Float,
    ) {
        val report = ReconstructionReadinessGate.evaluate(plan)
        if (report.ready) return

        val unresolvedOpeningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = max(4f, pixelsPerMeter * 0.055f)
            color = Color.argb(245, 226, 35, 120)
        }
        val unresolvedOpeningRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2.5f, pixelsPerMeter * 0.035f)
            color = Color.argb(245, 226, 35, 120)
        }
        report.unresolvedOpenings.forEach { gap ->
            drawOpeningAxis(
                canvas = canvas,
                transform = transform,
                center = gap.center,
                widthMeters = gap.widthMeters,
                rotationDegrees = gap.rotationDegrees,
                paint = unresolvedOpeningPaint,
            )
            val center = transform.planToImage(gap.center)
            canvas.drawCircle(
                center.first,
                center.second,
                max(8f, pixelsPerMeter * RECONSTRUCTION_MARKER_RADIUS_METERS),
                unresolvedOpeningRing,
            )
        }

        val unsupportedBoundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = max(4.5f, pixelsPerMeter * 0.06f)
            color = Color.argb(250, 238, 76, 32)
        }
        val boundaryHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = max(8f, pixelsPerMeter * 0.105f)
            color = Color.argb(88, 255, 171, 64)
        }
        report.unsupportedRoomBoundaries.forEach { issue ->
            val room = plan.rooms.firstOrNull { it.id == issue.roomId } ?: return@forEach
            if (room.polygon.size < 3 || issue.weakestEdgeIndex !in room.polygon.indices) return@forEach
            val a = room.polygon[issue.weakestEdgeIndex]
            val b = room.polygon[(issue.weakestEdgeIndex + 1) % room.polygon.size]
            val pa = transform.planToImage(a)
            val pb = transform.planToImage(b)
            canvas.drawLine(pa.first, pa.second, pb.first, pb.second, boundaryHaloPaint)
            canvas.drawLine(pa.first, pa.second, pb.first, pb.second, unsupportedBoundaryPaint)
        }

        val voidFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(56, 196, 34, 82)
        }
        val voidStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = max(3f, pixelsPerMeter * 0.04f)
            color = Color.argb(238, 196, 34, 82)
        }
        report.unsupportedVerticalVoids.forEach { room ->
            if (room.polygon.size < 3) return@forEach
            val path = Path()
            val first = transform.planToImage(room.polygon.first())
            path.moveTo(first.first, first.second)
            room.polygon.drop(1).forEach { point ->
                val pixel = transform.planToImage(point)
                path.lineTo(pixel.first, pixel.second)
            }
            path.close()
            canvas.drawPath(path, voidFill)
            canvas.drawPath(path, voidStroke)
        }
    }

    private fun projectOntoSegment(point: Vec2, wall: WallSegment): Vec2 {
        val vx = wall.end.x - wall.start.x
        val vz = wall.end.z - wall.start.z
        val lengthSq = vx * vx + vz * vz
        if (lengthSq <= 0.000001f) return wall.start
        val t = (((point.x - wall.start.x) * vx + (point.z - wall.start.z) * vz) / lengthSq)
            .coerceIn(0f, 1f)
        return Vec2(
            x = wall.start.x + vx * t,
            z = wall.start.z + vz * t,
        )
    }

    private fun drawOpeningAxis(
        canvas: Canvas,
        transform: PlanRasterTransform,
        center: Vec2,
        widthMeters: Float,
        rotationDegrees: Float,
        paint: Paint,
    ) {
        if (widthMeters <= 0f) return
        val radians = rotationDegrees * PI.toFloat() / 180f
        val ux = cos(radians)
        val uz = sin(radians)
        val half = widthMeters * 0.5f
        val a = Vec2(center.x - ux * half, center.z - uz * half)
        val b = Vec2(center.x + ux * half, center.z + uz * half)
        val (ax, ay) = transform.planToImage(a)
        val (bx, by) = transform.planToImage(b)
        canvas.drawLine(ax, ay, bx, by, paint)
    }

    private const val DEFAULT_MAX_SIDE = 1200
    private const val MIN_WALL_STROKE_PX = 2.5f
    private const val MIN_ENDPOINT_RADIUS_PX = 2.2f
    private const val MIN_COLUMN_OVERLAY_CONFIDENCE = 0.74f
    private const val TOPOLOGY_MARKER_RADIUS_METERS = 0.08f
    private const val RECONSTRUCTION_MARKER_RADIUS_METERS = 0.095f
}
