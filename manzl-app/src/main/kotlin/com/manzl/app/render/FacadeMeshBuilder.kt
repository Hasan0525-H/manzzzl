package com.manzl.app.render

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import com.manzl.app.model.WindowOpening
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Builds a thin exterior finish skin only on wall sides classified as exposed.
 *
 * The base wall remains untouched and keeps the interior material. The façade layer is offset a few
 * millimetres from the exposed face, so an interior wall face never becomes stone merely because the
 * opposite side faces outdoors. Door/window cut-outs are copied exactly from canonical openings.
 */
internal object FacadeMeshBuilder {

    fun build(
        plan: FloorPlan,
        wallHeightOverride: Float? = null,
        doorHeightOverride: Float? = null,
        minimumExposureConfidence: Float = DEFAULT_MIN_EXPOSURE_CONFIDENCE,
    ): FacadeMesh {
        val exposures = ExteriorWallClassifier.classify(plan)
            .filter { it.confidence >= minimumExposureConfidence }
            .associateBy { it.wallIndex }
        if (exposures.isEmpty()) return FacadeMesh.EMPTY

        val builder = SurfaceBuilder()
        val doorHeight = doorHeightOverride ?: DEFAULT_DOOR_HEIGHT_METERS
        plan.walls.forEachIndexed { index, sourceWall ->
            val exposure = exposures[index] ?: return@forEachIndexed
            val wall = if (wallHeightOverride == null) sourceWall else sourceWall.copy(heightMeters = wallHeightOverride)
            if (exposure.positiveNormalExterior) {
                addWallSide(
                    builder = builder,
                    wall = wall,
                    normalSign = 1f,
                    doors = plan.doors,
                    windows = plan.windows,
                    doorHeight = doorHeight,
                )
            }
            if (exposure.negativeNormalExterior) {
                addWallSide(
                    builder = builder,
                    wall = wall,
                    normalSign = -1f,
                    doors = plan.doors,
                    windows = plan.windows,
                    doorHeight = doorHeight,
                )
            }
        }
        return FacadeMesh(builder.vertices.toFloatArray(), builder.indices.toIntArray())
    }

    private fun addWallSide(
        builder: SurfaceBuilder,
        wall: WallSegment,
        normalSign: Float,
        doors: List<DoorOpening>,
        windows: List<WindowOpening>,
        doorHeight: Float,
    ) {
        val dx = wall.end.x - wall.start.x
        val dz = wall.end.z - wall.start.z
        val length = sqrt(dx * dx + dz * dz)
        if (length <= EPSILON || wall.heightMeters <= EPSILON) return
        val axisX = dx / length
        val axisZ = dz / length
        val normalX = -axisZ * normalSign
        val normalZ = axisX * normalSign
        val faceOffset = wall.thicknessMeters * 0.5f + SKIN_OFFSET_METERS

        val cutouts = ArrayList<Cutout>()
        doors.forEach { door ->
            projectCutout(
                wall = wall,
                length = length,
                axisX = axisX,
                axisZ = axisZ,
                center = door.center,
                width = door.widthMeters,
                bottom = 0f,
                top = min(doorHeight, wall.heightMeters),
            )?.let(cutouts::add)
        }
        windows.forEach { window ->
            projectCutout(
                wall = wall,
                length = length,
                axisX = axisX,
                axisZ = axisZ,
                center = window.center,
                width = window.widthMeters,
                bottom = window.sillHeightMeters.coerceAtLeast(0f),
                top = (window.sillHeightMeters + window.heightMeters).coerceAtMost(wall.heightMeters),
            )?.let(cutouts::add)
        }

        val breaks = buildList {
            add(0f)
            add(length)
            cutouts.forEach { cutout ->
                add(cutout.from)
                add(cutout.to)
            }
        }.map { it.coerceIn(0f, length) }
            .distinct()
            .sorted()

        for (index in 0 until breaks.lastIndex) {
            val from = breaks[index]
            val to = breaks[index + 1]
            if (to - from <= EPSILON) continue
            val middle = (from + to) * 0.5f
            val verticalVoids = cutouts
                .filter { middle > it.from + EPSILON && middle < it.to - EPSILON }
                .map { it.bottom to it.top }
                .sortedBy { it.first }
            val solids = complementVerticalRanges(verticalVoids, wall.heightMeters)
            solids.forEach { (bottom, top) ->
                if (top - bottom <= EPSILON) return@forEach
                val a = pointOnFace(wall, axisX, axisZ, normalX, normalZ, faceOffset, from, bottom)
                val b = pointOnFace(wall, axisX, axisZ, normalX, normalZ, faceOffset, to, bottom)
                val c = pointOnFace(wall, axisX, axisZ, normalX, normalZ, faceOffset, to, top)
                val d = pointOnFace(wall, axisX, axisZ, normalX, normalZ, faceOffset, from, top)
                builder.addQuad(a, b, c, d, P3(normalX, 0f, normalZ))
            }
        }
    }

    private fun projectCutout(
        wall: WallSegment,
        length: Float,
        axisX: Float,
        axisZ: Float,
        center: Vec2,
        width: Float,
        bottom: Float,
        top: Float,
    ): Cutout? {
        if (width <= 0f || top - bottom <= EPSILON) return null
        val relX = center.x - wall.start.x
        val relZ = center.z - wall.start.z
        val along = relX * axisX + relZ * axisZ
        val perpendicular = abs(relX * -axisZ + relZ * axisX)
        val tolerance = wall.thicknessMeters * 0.5f + OPENING_WALL_TOLERANCE_METERS
        if (perpendicular > tolerance) return null
        val half = width * 0.5f
        val from = max(0f, along - half)
        val to = min(length, along + half)
        if (to - from < MIN_OPENING_SPAN_METERS) return null
        return Cutout(from, to, bottom.coerceAtLeast(0f), top.coerceAtMost(wall.heightMeters))
    }

    private fun complementVerticalRanges(voids: List<Pair<Float, Float>>, height: Float): List<Pair<Float, Float>> {
        if (voids.isEmpty()) return listOf(0f to height)
        val merged = ArrayList<Pair<Float, Float>>()
        voids.forEach { raw ->
            val start = raw.first.coerceIn(0f, height)
            val end = raw.second.coerceIn(0f, height)
            if (end - start <= EPSILON) return@forEach
            val previous = merged.lastOrNull()
            if (previous == null || start > previous.second + EPSILON) {
                merged += start to end
            } else {
                merged[merged.lastIndex] = previous.first to max(previous.second, end)
            }
        }
        if (merged.isEmpty()) return listOf(0f to height)

        val solids = ArrayList<Pair<Float, Float>>()
        var cursor = 0f
        merged.forEach { void ->
            if (void.first > cursor + EPSILON) solids += cursor to void.first
            cursor = max(cursor, void.second)
        }
        if (cursor < height - EPSILON) solids += cursor to height
        return solids
    }

    private fun pointOnFace(
        wall: WallSegment,
        axisX: Float,
        axisZ: Float,
        normalX: Float,
        normalZ: Float,
        faceOffset: Float,
        along: Float,
        y: Float,
    ) = P3(
        x = wall.start.x + axisX * along + normalX * faceOffset,
        y = y,
        z = wall.start.z + axisZ * along + normalZ * faceOffset,
    )

    private data class Cutout(val from: Float, val to: Float, val bottom: Float, val top: Float)

    private data class P3(val x: Float, val y: Float, val z: Float)

    private class SurfaceBuilder {
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Int>()

        fun addQuad(a: P3, b: P3, c: P3, d: P3, normal: P3) {
            val base = vertices.size / FLOATS_PER_VERTEX
            addVertex(a, normal)
            addVertex(b, normal)
            addVertex(c, normal)
            addVertex(d, normal)
            indices += base
            indices += base + 1
            indices += base + 2
            indices += base
            indices += base + 2
            indices += base + 3
        }

        private fun addVertex(point: P3, normal: P3) {
            vertices += point.x
            vertices += point.y
            vertices += point.z
            vertices += normal.x
            vertices += normal.y
            vertices += normal.z
        }
    }

    private const val FLOATS_PER_VERTEX = 6
    private const val DEFAULT_DOOR_HEIGHT_METERS = 2.20f
    private const val DEFAULT_MIN_EXPOSURE_CONFIDENCE = 0.70f
    private const val SKIN_OFFSET_METERS = 0.006f
    private const val OPENING_WALL_TOLERANCE_METERS = 0.24f
    private const val MIN_OPENING_SPAN_METERS = 0.20f
    private const val EPSILON = 0.0001f
}

internal data class FacadeMesh(
    val vertices: FloatArray,
    val indices: IntArray,
) {
    companion object {
        val EMPTY = FacadeMesh(floatArrayOf(), intArrayOf())
    }
}
