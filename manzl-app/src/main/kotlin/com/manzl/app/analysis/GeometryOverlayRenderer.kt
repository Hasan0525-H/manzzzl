package com.manzl.app.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityIssueKind
import com.manzl.app.model.GeometryFidelityStatus
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Renders the extracted geometry directly over the uploaded plan.
 *
 * This is a development/user trust surface, not a decorative preview: if a wall is missing, shifted
 * or stops just short of a real junction, the mismatch is visible before the user is allowed into
 * 3D. The source bitmap is never modified.
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

        // Draw these after walls so the exact crack remains visible instead of being hidden under the
        // regular amber/green endpoint dots. The marker is diagnostic only and never snaps geometry.
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

        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        return output
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
    private const val TOPOLOGY_MARKER_RADIUS_METERS = 0.08f
}
