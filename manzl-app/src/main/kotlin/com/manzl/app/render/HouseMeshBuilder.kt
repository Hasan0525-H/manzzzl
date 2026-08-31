package com.manzl.app.render

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WallSegment
import com.manzl.app.model.WindowOpening
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal data class MeshData(
    val wallVertices: FloatArray,
    val wallIndices: IntArray,
    val floorVertices: FloatArray,
    val floorIndices: IntArray,
    val ceilingVertices: FloatArray,
    val ceilingIndices: IntArray,
    val trimVertices: FloatArray,
    val trimIndices: IntArray,
    val glassVertices: FloatArray,
    val glassIndices: IntArray,
)

internal object HouseMeshBuilder {

    fun build(
        plan: FloorPlan,
        wallHeightOverride: Float? = null,
        doorHeightOverride: Float? = null,
    ): MeshData {
        val doorHeight = doorHeightOverride ?: DEFAULT_DOOR_HEIGHT_METERS
        val ceilingHeight = wallHeightOverride
            ?: plan.walls.maxOfOrNull { it.heightMeters }
            ?: DEFAULT_WALL_HEIGHT_METERS

        val wallBuilder = GeometryBuilder()
        for (sourceWall in plan.walls) {
            val wall = if (wallHeightOverride == null) {
                sourceWall
            } else {
                sourceWall.copy(heightMeters = wallHeightOverride)
            }
            addWallWithOpenings(
                builder = wallBuilder,
                wall = wall,
                doors = plan.doors,
                windows = plan.windows,
                doorHeight = doorHeight,
            )
        }

        val floorBuilder = GeometryBuilder()
        val halfWidth = plan.widthMeters / 2f + 0.25f
        val halfDepth = plan.depthMeters / 2f + 0.25f
        floorBuilder.addQuad(
            a = P3(-halfWidth, 0f, -halfDepth),
            b = P3(halfWidth, 0f, -halfDepth),
            c = P3(halfWidth, 0f, halfDepth),
            d = P3(-halfWidth, 0f, halfDepth),
            normal = P3(0f, 1f, 0f),
        )

        // Ceilings are generated only from validated room polygons. This intentionally avoids
        // covering courtyards, double-height voids or uncertain open areas with one giant slab.
        val ceilingBuilder = GeometryBuilder()
        plan.rooms.forEach { room ->
            addRoomCeiling(ceilingBuilder, room, ceilingHeight)
        }

        val trimBuilder = GeometryBuilder()
        val glassBuilder = GeometryBuilder()
        for (door in plan.doors) {
            addDoorFrame(trimBuilder, door, doorHeight)
        }
        for (window in plan.windows) {
            addWindowFrame(trimBuilder, window)
            addWindowGlass(glassBuilder, window)
        }

        return MeshData(
            wallVertices = wallBuilder.vertices.toFloatArray(),
            wallIndices = wallBuilder.indices.toIntArray(),
            floorVertices = floorBuilder.vertices.toFloatArray(),
            floorIndices = floorBuilder.indices.toIntArray(),
            ceilingVertices = ceilingBuilder.vertices.toFloatArray(),
            ceilingIndices = ceilingBuilder.indices.toIntArray(),
            trimVertices = trimBuilder.vertices.toFloatArray(),
            trimIndices = trimBuilder.indices.toIntArray(),
            glassVertices = glassBuilder.vertices.toFloatArray(),
            glassIndices = glassBuilder.indices.toIntArray(),
        )
    }

    /**
     * Produces true rectangular openings, rather than painting a door/window over a solid wall.
     * The wall is split longitudinally at opening edges and vertically around every active opening.
     */
    private fun addWallWithOpenings(
        builder: GeometryBuilder,
        wall: WallSegment,
        doors: List<DoorOpening>,
        windows: List<WindowOpening>,
        doorHeight: Float,
    ) {
        val dx = wall.end.x - wall.start.x
        val dz = wall.end.z - wall.start.z
        val length = sqrt(dx * dx + dz * dz)
        if (length < EPSILON) return
        val alongX = dx / length
        val alongZ = dz / length

        val openings = ArrayList<WallCutout>()
        doors.forEach { door ->
            cutoutForOpening(
                wall = wall,
                center = door.center,
                width = door.widthMeters,
                bottom = 0f,
                top = min(doorHeight, wall.heightMeters),
            )?.let(openings::add)
        }
        windows.forEach { window ->
            cutoutForOpening(
                wall = wall,
                center = window.center,
                width = window.widthMeters,
                bottom = window.sillHeightMeters.coerceAtLeast(0f),
                top = (window.sillHeightMeters + window.heightMeters).coerceAtMost(wall.heightMeters),
            )?.let(openings::add)
        }

        if (openings.isEmpty()) {
            addWallBox(builder, wall, alongX, alongZ)
            return
        }

        val cuts = ArrayList<Float>()
        cuts += 0f
        cuts += length
        for (opening in openings) {
            cuts += opening.from.coerceIn(0f, length)
            cuts += opening.to.coerceIn(0f, length)
        }
        val sortedCuts = cuts
            .sorted()
            .fold(ArrayList<Float>()) { result, value ->
                if (result.isEmpty() || abs(result.last() - value) > 0.002f) result += value
                result
            }

        for (index in 0 until sortedCuts.lastIndex) {
            val from = sortedCuts[index]
            val to = sortedCuts[index + 1]
            if (to - from < MIN_SOLID_SLICE_METERS) continue
            val midpoint = (from + to) * 0.5f
            val verticalVoids = openings
                .filter { midpoint > it.from + OPENING_EDGE_EPSILON && midpoint < it.to - OPENING_EDGE_EPSILON }
                .map { it.bottom.coerceIn(0f, wall.heightMeters) to it.top.coerceIn(0f, wall.heightMeters) }
                .filter { it.second - it.first > MIN_SOLID_SLICE_METERS }
                .sortedBy { it.first }

            val solidSpans = complementVerticalSpans(
                height = wall.heightMeters,
                voids = verticalVoids,
            )
            for ((bottom, top) in solidSpans) {
                addWallSlice(
                    builder = builder,
                    wall = wall,
                    alongX = alongX,
                    alongZ = alongZ,
                    from = from,
                    to = to,
                    bottom = bottom,
                    top = top,
                )
            }
        }
    }

    private fun cutoutForOpening(
        wall: WallSegment,
        center: Vec2,
        width: Float,
        bottom: Float,
        top: Float,
    ): WallCutout? {
        if (width <= 0f || top <= bottom) return null
        val dx = wall.end.x - wall.start.x
        val dz = wall.end.z - wall.start.z
        val lengthSq = dx * dx + dz * dz
        if (lengthSq < EPSILON) return null
        val length = sqrt(lengthSq)
        val projection = ((center.x - wall.start.x) * dx + (center.z - wall.start.z) * dz) / lengthSq
        val projectedX = wall.start.x + dx * projection.coerceIn(0f, 1f)
        val projectedZ = wall.start.z + dz * projection.coerceIn(0f, 1f)
        val perpendicularDistance = sqrt(
            (center.x - projectedX) * (center.x - projectedX) +
                (center.z - projectedZ) * (center.z - projectedZ)
        )
        if (perpendicularDistance > wall.thicknessMeters + OPENING_ASSOCIATION_METERS) return null

        val centerAlong = projection * length
        val from = centerAlong - width / 2f
        val to = centerAlong + width / 2f
        if (to < -OPENING_ASSOCIATION_METERS || from > length + OPENING_ASSOCIATION_METERS) return null
        return WallCutout(
            from = from.coerceIn(0f, length),
            to = to.coerceIn(0f, length),
            bottom = bottom,
            top = top,
        )
    }

    private fun complementVerticalSpans(
        height: Float,
        voids: List<Pair<Float, Float>>,
    ): List<Pair<Float, Float>> {
        if (voids.isEmpty()) return listOf(0f to height)
        val merged = ArrayList<Pair<Float, Float>>()
        for ((rawBottom, rawTop) in voids) {
            val bottom = rawBottom.coerceIn(0f, height)
            val top = rawTop.coerceIn(0f, height)
            if (top <= bottom) continue
            if (merged.isEmpty() || bottom > merged.last().second + 0.002f) {
                merged += bottom to top
            } else {
                val previous = merged.removeAt(merged.lastIndex)
                merged += previous.first to max(previous.second, top)
            }
        }

        val solid = ArrayList<Pair<Float, Float>>()
        var cursor = 0f
        for ((bottom, top) in merged) {
            if (bottom - cursor >= MIN_SOLID_SLICE_METERS) solid += cursor to bottom
            cursor = max(cursor, top)
        }
        if (height - cursor >= MIN_SOLID_SLICE_METERS) solid += cursor to height
        return solid
    }

    private fun addWallBox(
        builder: GeometryBuilder,
        wall: WallSegment,
        alongX: Float,
        alongZ: Float,
    ) {
        val dx = wall.end.x - wall.start.x
        val dz = wall.end.z - wall.start.z
        val length = sqrt(dx * dx + dz * dz)
        addOrientedBox(
            builder = builder,
            centerX = (wall.start.x + wall.end.x) * 0.5f,
            centerY = wall.heightMeters * 0.5f,
            centerZ = (wall.start.z + wall.end.z) * 0.5f,
            halfAlong = length * 0.5f,
            halfDepth = wall.thicknessMeters * 0.5f,
            halfHeight = wall.heightMeters * 0.5f,
            alongX = alongX,
            alongZ = alongZ,
        )
    }

    private fun addWallSlice(
        builder: GeometryBuilder,
        wall: WallSegment,
        alongX: Float,
        alongZ: Float,
        from: Float,
        to: Float,
        bottom: Float,
        top: Float,
    ) {
        val length = to - from
        val midpoint = (from + to) * 0.5f
        addOrientedBox(
            builder = builder,
            centerX = wall.start.x + alongX * midpoint,
            centerY = (bottom + top) * 0.5f,
            centerZ = wall.start.z + alongZ * midpoint,
            halfAlong = length * 0.5f,
            halfDepth = wall.thicknessMeters * 0.5f,
            halfHeight = (top - bottom) * 0.5f,
            alongX = alongX,
            alongZ = alongZ,
        )
    }

    private fun addRoomCeiling(builder: GeometryBuilder, room: RoomRegion, height: Float) {
        if (room.confidence < MIN_CEILING_ROOM_CONFIDENCE || room.polygon.size < 4) return
        val minX = room.polygon.minOf { it.x }
        val maxX = room.polygon.maxOf { it.x }
        val minZ = room.polygon.minOf { it.z }
        val maxZ = room.polygon.maxOf { it.z }
        val width = maxX - minX
        val depth = maxZ - minZ
        val area = width * depth
        if (width < MIN_CEILING_SPAN_METERS || depth < MIN_CEILING_SPAN_METERS) return
        if (area <= 0f || area > MAX_CEILING_AREA_SQ_METERS) return

        // Only render polygons that are effectively axis-aligned rectangles. More complex semantic
        // room polygons will later use the triangulation path instead of being approximated here.
        val rectangular = room.polygon.all { point ->
            val onXEdge = abs(point.x - minX) <= RECTANGLE_CORNER_TOLERANCE_METERS ||
                abs(point.x - maxX) <= RECTANGLE_CORNER_TOLERANCE_METERS
            val onZEdge = abs(point.z - minZ) <= RECTANGLE_CORNER_TOLERANCE_METERS ||
                abs(point.z - maxZ) <= RECTANGLE_CORNER_TOLERANCE_METERS
            onXEdge && onZEdge
        }
        if (!rectangular) return

        val inset = min(CEILING_INSET_METERS, min(width, depth) * 0.04f)
        val x0 = minX + inset
        val x1 = maxX - inset
        val z0 = minZ + inset
        val z1 = maxZ - inset
        if (x1 <= x0 || z1 <= z0) return

        builder.addQuad(
            a = P3(x0, height, z0),
            b = P3(x0, height, z1),
            c = P3(x1, height, z1),
            d = P3(x1, height, z0),
            normal = P3(0f, -1f, 0f),
        )
    }

    private fun addDoorFrame(builder: GeometryBuilder, door: DoorOpening, doorHeight: Float) {
        val direction = directionForRotation(door.rotationDegrees)
        val halfGap = door.widthMeters / 2f
        val postOffset = halfGap + DOOR_JAMB_WIDTH_METERS / 2f

        addOrientedBox(
            builder = builder,
            centerX = door.center.x - direction.first * postOffset,
            centerY = doorHeight / 2f,
            centerZ = door.center.z - direction.second * postOffset,
            halfAlong = DOOR_JAMB_WIDTH_METERS / 2f,
            halfDepth = DOOR_FRAME_DEPTH_METERS / 2f,
            halfHeight = doorHeight / 2f,
            alongX = direction.first,
            alongZ = direction.second,
        )
        addOrientedBox(
            builder = builder,
            centerX = door.center.x + direction.first * postOffset,
            centerY = doorHeight / 2f,
            centerZ = door.center.z + direction.second * postOffset,
            halfAlong = DOOR_JAMB_WIDTH_METERS / 2f,
            halfDepth = DOOR_FRAME_DEPTH_METERS / 2f,
            halfHeight = doorHeight / 2f,
            alongX = direction.first,
            alongZ = direction.second,
        )
        addOrientedBox(
            builder = builder,
            centerX = door.center.x,
            centerY = doorHeight + DOOR_LINTEL_HEIGHT_METERS / 2f,
            centerZ = door.center.z,
            halfAlong = halfGap + DOOR_JAMB_WIDTH_METERS,
            halfDepth = DOOR_FRAME_DEPTH_METERS / 2f,
            halfHeight = DOOR_LINTEL_HEIGHT_METERS / 2f,
            alongX = direction.first,
            alongZ = direction.second,
        )
    }

    private fun addWindowFrame(builder: GeometryBuilder, window: WindowOpening) {
        val direction = directionForRotation(window.rotationDegrees)
        val halfWidth = window.widthMeters / 2f
        val frameY = window.sillHeightMeters + window.heightMeters / 2f
        val sideOffset = halfWidth + WINDOW_FRAME_WIDTH_METERS / 2f

        for (sign in listOf(-1f, 1f)) {
            addOrientedBox(
                builder = builder,
                centerX = window.center.x + direction.first * sideOffset * sign,
                centerY = frameY,
                centerZ = window.center.z + direction.second * sideOffset * sign,
                halfAlong = WINDOW_FRAME_WIDTH_METERS / 2f,
                halfDepth = WINDOW_FRAME_DEPTH_METERS / 2f,
                halfHeight = window.heightMeters / 2f + WINDOW_FRAME_WIDTH_METERS,
                alongX = direction.first,
                alongZ = direction.second,
            )
        }
        for (sign in listOf(-1f, 1f)) {
            addOrientedBox(
                builder = builder,
                centerX = window.center.x,
                centerY = frameY + sign * (window.heightMeters / 2f + WINDOW_FRAME_WIDTH_METERS / 2f),
                centerZ = window.center.z,
                halfAlong = halfWidth + WINDOW_FRAME_WIDTH_METERS,
                halfDepth = WINDOW_FRAME_DEPTH_METERS / 2f,
                halfHeight = WINDOW_FRAME_WIDTH_METERS / 2f,
                alongX = direction.first,
                alongZ = direction.second,
            )
        }
    }

    private fun addWindowGlass(builder: GeometryBuilder, window: WindowOpening) {
        val direction = directionForRotation(window.rotationDegrees)
        addOrientedBox(
            builder = builder,
            centerX = window.center.x,
            centerY = window.sillHeightMeters + window.heightMeters / 2f,
            centerZ = window.center.z,
            halfAlong = (window.widthMeters / 2f - WINDOW_GLASS_INSET_METERS).coerceAtLeast(0.05f),
            halfDepth = WINDOW_GLASS_THICKNESS_METERS / 2f,
            halfHeight = (window.heightMeters / 2f - WINDOW_GLASS_INSET_METERS).coerceAtLeast(0.05f),
            alongX = direction.first,
            alongZ = direction.second,
        )
    }

    private fun directionForRotation(rotationDegrees: Float): Pair<Float, Float> {
        val radians = rotationDegrees * (PI.toFloat() / 180f)
        return cos(radians) to sin(radians)
    }

    private fun addOrientedBox(
        builder: GeometryBuilder,
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        halfAlong: Float,
        halfDepth: Float,
        halfHeight: Float,
        alongX: Float,
        alongZ: Float,
    ) {
        if (halfAlong <= 0f || halfDepth <= 0f || halfHeight <= 0f) return
        val normalX = -alongZ
        val normalZ = alongX

        fun corner(along: Float, depth: Float, y: Float) = P3(
            x = centerX + alongX * along + normalX * depth,
            y = centerY + y,
            z = centerZ + alongZ * along + normalZ * depth,
        )

        val a0 = corner(-halfAlong, halfDepth, -halfHeight)
        val b0 = corner(halfAlong, halfDepth, -halfHeight)
        val c0 = corner(halfAlong, -halfDepth, -halfHeight)
        val d0 = corner(-halfAlong, -halfDepth, -halfHeight)
        val a1 = corner(-halfAlong, halfDepth, halfHeight)
        val b1 = corner(halfAlong, halfDepth, halfHeight)
        val c1 = corner(halfAlong, -halfDepth, halfHeight)
        val d1 = corner(-halfAlong, -halfDepth, halfHeight)

        builder.addQuad(a0, b0, b1, a1, P3(normalX, 0f, normalZ))
        builder.addQuad(c0, d0, d1, c1, P3(-normalX, 0f, -normalZ))
        builder.addQuad(d0, a0, a1, d1, P3(-alongX, 0f, -alongZ))
        builder.addQuad(b0, c0, c1, b1, P3(alongX, 0f, alongZ))
        builder.addQuad(a1, b1, c1, d1, P3(0f, 1f, 0f))
        builder.addQuad(d0, c0, b0, a0, P3(0f, -1f, 0f))
    }

    private class GeometryBuilder {
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

        private fun addVertex(position: P3, normal: P3) {
            vertices += position.x
            vertices += position.y
            vertices += position.z
            vertices += normal.x
            vertices += normal.y
            vertices += normal.z
        }
    }

    private data class WallCutout(
        val from: Float,
        val to: Float,
        val bottom: Float,
        val top: Float,
    )

    private data class P3(
        val x: Float,
        val y: Float,
        val z: Float,
    )

    private const val DEFAULT_WALL_HEIGHT_METERS = 3.0f
    private const val DEFAULT_DOOR_HEIGHT_METERS = 2.20f
    private const val DOOR_JAMB_WIDTH_METERS = 0.075f
    private const val DOOR_LINTEL_HEIGHT_METERS = 0.085f
    private const val DOOR_FRAME_DEPTH_METERS = 0.20f
    private const val WINDOW_FRAME_WIDTH_METERS = 0.055f
    private const val WINDOW_FRAME_DEPTH_METERS = 0.15f
    private const val WINDOW_GLASS_THICKNESS_METERS = 0.014f
    private const val WINDOW_GLASS_INSET_METERS = 0.045f
    private const val OPENING_ASSOCIATION_METERS = 0.30f
    private const val OPENING_EDGE_EPSILON = 0.001f
    private const val MIN_SOLID_SLICE_METERS = 0.012f
    private const val MIN_CEILING_ROOM_CONFIDENCE = 0.68f
    private const val MIN_CEILING_SPAN_METERS = 0.75f
    private const val MAX_CEILING_AREA_SQ_METERS = 100f
    private const val RECTANGLE_CORNER_TOLERANCE_METERS = 0.18f
    private const val CEILING_INSET_METERS = 0.025f
    private const val EPSILON = 0.000001f
    private const val FLOATS_PER_VERTEX = 6
}
