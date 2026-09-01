package com.manzl.app.render

import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import kotlin.math.max
import kotlin.math.min

/**
 * Adds restrained, room-aware staging without changing canonical architecture.
 *
 * Staging is presentation-only: it is generated only for high-confidence labelled rooms, must fit
 * completely inside the measured room polygon, and never adds/moves walls, doors, windows or room
 * boundaries. The goal is to make the first 3D result read as a furnished home instead of an empty
 * extrusion while preserving the source plan as geometric truth.
 *
 * We intentionally reuse the existing material batches so this remains cheap on mobile:
 * - floor batch: soft/neutral upholstered masses,
 * - wall batch: light stone/counter masses,
 * - trim batch: wood joinery/furniture.
 */
internal object InteriorStagingMeshBuilder {

    fun build(plan: FloorPlan): MeshData {
        val neutral = PrimitiveBuilder()
        val stone = PrimitiveBuilder()
        val wood = PrimitiveBuilder()

        plan.rooms
            .asSequence()
            .filter { it.confidence >= MIN_ROOM_CONFIDENCE }
            .filter { !it.label.isNullOrBlank() }
            .forEach { room ->
                val frame = RoomFrame.from(room) ?: return@forEach
                if (frame.area < MIN_STAGEABLE_AREA_SQ_METERS) return@forEach
                when (classify(room.label.orEmpty())) {
                    RoomKind.MAJLIS -> stageMajlis(room, frame, neutral, wood)
                    RoomKind.LIVING -> stageLiving(room, frame, neutral, wood)
                    RoomKind.MASTER_BEDROOM -> stageBedroom(room, frame, neutral, wood, master = true)
                    RoomKind.BEDROOM -> stageBedroom(room, frame, neutral, wood, master = false)
                    RoomKind.DINING -> stageDining(room, frame, neutral, wood)
                    RoomKind.KITCHEN -> stageKitchen(room, frame, stone, wood)
                    RoomKind.OFFICE -> stageOffice(room, frame, neutral, wood)
                    RoomKind.DRESSING -> stageDressing(room, frame, wood)
                    RoomKind.BATHROOM -> stageBathroom(room, frame, stone, wood)
                    RoomKind.NONE -> Unit
                }
            }

        return MeshData(
            wallVertices = stone.vertices.toFloatArray(),
            wallIndices = stone.indices.toIntArray(),
            floorVertices = neutral.vertices.toFloatArray(),
            floorIndices = neutral.indices.toIntArray(),
            ceilingVertices = floatArrayOf(),
            ceilingIndices = intArrayOf(),
            trimVertices = wood.vertices.toFloatArray(),
            trimIndices = wood.indices.toIntArray(),
            glassVertices = floatArrayOf(),
            glassIndices = intArrayOf(),
        )
    }

    private fun stageMajlis(
        room: RoomRegion,
        frame: RoomFrame,
        neutral: PrimitiveBuilder,
        wood: PrimitiveBuilder,
    ) {
        val sofaLength = min(2.55f, frame.longSpan * 0.60f)
        val sofaDepth = min(0.88f, frame.shortSpan * 0.24f)
        if (sofaLength < 1.45f || sofaDepth < 0.58f) return
        val offset = frame.shortSpan * 0.29f
        addSofa(room, frame, neutral, sofaLength, sofaDepth, -offset)
        addSofa(room, frame, neutral, sofaLength, sofaDepth, offset)
        addTable(room, frame, wood, 0f, 0f, min(1.25f, frame.longSpan * 0.30f), 0.62f, 0.34f)
    }

    private fun stageLiving(
        room: RoomRegion,
        frame: RoomFrame,
        neutral: PrimitiveBuilder,
        wood: PrimitiveBuilder,
    ) {
        val sofaLength = min(2.45f, frame.longSpan * 0.58f)
        val sofaDepth = min(0.88f, frame.shortSpan * 0.25f)
        if (sofaLength < 1.40f || sofaDepth < 0.58f) return
        addSofa(room, frame, neutral, sofaLength, sofaDepth, -frame.shortSpan * 0.27f)
        addTable(room, frame, wood, 0.10f, frame.shortSpan * 0.06f, min(1.10f, frame.longSpan * 0.28f), 0.58f, 0.34f)
    }

    private fun stageBedroom(
        room: RoomRegion,
        frame: RoomFrame,
        neutral: PrimitiveBuilder,
        wood: PrimitiveBuilder,
        master: Boolean,
    ) {
        val bedLength = min(2.10f, frame.longSpan * 0.62f)
        val bedWidth = min(if (master) 1.85f else 1.55f, frame.shortSpan * 0.62f)
        if (bedLength < 1.65f || bedWidth < 0.92f) return

        addSafeBox(room, frame, wood, 0f, 0f, bedLength, bedWidth, 0.24f, 0.24f)
        addSafeBox(room, frame, neutral, 0f, 0f, bedLength * 0.94f, bedWidth * 0.94f, 0.25f, 0.47f)
        addSafeBox(
            room,
            frame,
            wood,
            -bedLength * 0.52f,
            0f,
            0.10f,
            bedWidth,
            0.92f,
            0.70f,
        )

        val sideSize = min(0.52f, max(0.36f, (frame.shortSpan - bedWidth) * 0.28f))
        if (sideSize >= 0.36f) {
            val sideOffset = bedWidth * 0.5f + sideSize * 0.68f
            addSafeBox(room, frame, wood, -bedLength * 0.30f, sideOffset, sideSize, sideSize, 0.46f, 0.23f)
            addSafeBox(room, frame, wood, -bedLength * 0.30f, -sideOffset, sideSize, sideSize, 0.46f, 0.23f)
        }
    }

    private fun stageDining(
        room: RoomRegion,
        frame: RoomFrame,
        neutral: PrimitiveBuilder,
        wood: PrimitiveBuilder,
    ) {
        val tableLength = min(2.05f, frame.longSpan * 0.52f)
        val tableWidth = min(0.95f, frame.shortSpan * 0.38f)
        if (tableLength < 1.20f || tableWidth < 0.65f) return
        addSafeBox(room, frame, wood, 0f, 0f, tableLength, tableWidth, 0.10f, 0.75f)

        val chairLong = min(0.46f, tableLength * 0.24f)
        val chairWide = min(0.46f, tableWidth * 0.56f)
        val chairV = tableWidth * 0.5f + chairWide * 0.72f
        val chairU = tableLength * 0.28f
        listOf(-chairU, chairU).forEach { u ->
            addSafeBox(room, frame, neutral, u, chairV, chairLong, chairWide, 0.14f, 0.47f)
            addSafeBox(room, frame, neutral, u, -chairV, chairLong, chairWide, 0.14f, 0.47f)
        }
    }

    private fun stageKitchen(
        room: RoomRegion,
        frame: RoomFrame,
        stone: PrimitiveBuilder,
        wood: PrimitiveBuilder,
    ) {
        val islandLength = min(1.90f, frame.longSpan * 0.45f)
        val islandWidth = min(0.90f, frame.shortSpan * 0.34f)
        if (islandLength >= 1.10f && islandWidth >= 0.62f) {
            addSafeBox(room, frame, wood, 0f, 0f, islandLength * 0.94f, islandWidth * 0.94f, 0.82f, 0.41f)
            addSafeBox(room, frame, stone, 0f, 0f, islandLength, islandWidth, 0.08f, 0.86f)
        }

        val cabinetLength = min(2.40f, frame.longSpan * 0.62f)
        val cabinetDepth = min(0.58f, frame.shortSpan * 0.20f)
        if (cabinetLength >= 1.20f && cabinetDepth >= 0.42f) {
            val v = -frame.shortSpan * 0.35f
            addSafeBox(room, frame, wood, 0f, v, cabinetLength, cabinetDepth, 0.82f, 0.41f)
            addSafeBox(room, frame, stone, 0f, v, cabinetLength, cabinetDepth + 0.04f, 0.06f, 0.85f)
        }
    }

    private fun stageOffice(
        room: RoomRegion,
        frame: RoomFrame,
        neutral: PrimitiveBuilder,
        wood: PrimitiveBuilder,
    ) {
        val deskLength = min(1.55f, frame.longSpan * 0.48f)
        val deskDepth = min(0.70f, frame.shortSpan * 0.27f)
        if (deskLength < 0.95f || deskDepth < 0.48f) return
        addSafeBox(room, frame, wood, 0f, -frame.shortSpan * 0.24f, deskLength, deskDepth, 0.09f, 0.75f)
        addSafeBox(room, frame, neutral, 0f, frame.shortSpan * 0.08f, 0.52f, 0.52f, 0.16f, 0.48f)
    }

    private fun stageDressing(room: RoomRegion, frame: RoomFrame, wood: PrimitiveBuilder) {
        val run = min(2.60f, frame.longSpan * 0.68f)
        val depth = min(0.62f, frame.shortSpan * 0.22f)
        if (run < 1.20f || depth < 0.42f) return
        addSafeBox(room, frame, wood, 0f, -frame.shortSpan * 0.34f, run, depth, 2.20f, 1.10f)
    }

    private fun stageBathroom(
        room: RoomRegion,
        frame: RoomFrame,
        stone: PrimitiveBuilder,
        wood: PrimitiveBuilder,
    ) {
        if (frame.area < 3.2f) return
        val vanityLength = min(1.25f, frame.longSpan * 0.45f)
        val vanityDepth = min(0.52f, frame.shortSpan * 0.25f)
        if (vanityLength < 0.70f || vanityDepth < 0.38f) return
        val v = -frame.shortSpan * 0.28f
        addSafeBox(room, frame, wood, 0f, v, vanityLength, vanityDepth, 0.72f, 0.36f)
        addSafeBox(room, frame, stone, 0f, v, vanityLength + 0.04f, vanityDepth + 0.03f, 0.07f, 0.75f)
    }

    private fun addSofa(
        room: RoomRegion,
        frame: RoomFrame,
        builder: PrimitiveBuilder,
        length: Float,
        depth: Float,
        v: Float,
    ) {
        addSafeBox(room, frame, builder, 0f, v, length, depth, 0.32f, 0.30f)
        val backV = v + if (v < 0f) -depth * 0.40f else depth * 0.40f
        addSafeBox(room, frame, builder, 0f, backV, length, 0.16f, 0.58f, 0.61f)
    }

    private fun addTable(
        room: RoomRegion,
        frame: RoomFrame,
        builder: PrimitiveBuilder,
        u: Float,
        v: Float,
        length: Float,
        depth: Float,
        height: Float,
    ) {
        addSafeBox(room, frame, builder, u, v, length, depth, 0.08f, height)
    }

    private fun addSafeBox(
        room: RoomRegion,
        frame: RoomFrame,
        builder: PrimitiveBuilder,
        localU: Float,
        localV: Float,
        length: Float,
        depth: Float,
        height: Float,
        centerY: Float,
    ) {
        if (length <= 0f || depth <= 0f || height <= 0f) return
        val center = frame.toWorld(localU, localV)
        val halfU = length * 0.5f
        val halfV = depth * 0.5f
        val corners = listOf(
            frame.toWorld(localU - halfU, localV - halfV),
            frame.toWorld(localU + halfU, localV - halfV),
            frame.toWorld(localU + halfU, localV + halfV),
            frame.toWorld(localU - halfU, localV + halfV),
        )
        if (corners.any { !pointInsidePolygon(it, room.polygon) }) return
        builder.addBox(
            centerX = center.x,
            centerY = centerY,
            centerZ = center.z,
            halfAlong = halfU,
            halfDepth = halfV,
            halfHeight = height * 0.5f,
            alongX = frame.axisX,
            alongZ = frame.axisZ,
        )
    }

    private fun classify(label: String): RoomKind = when {
        label.contains("مجلس") -> RoomKind.MAJLIS
        label.contains("صالة") || label.contains("بهو") -> RoomKind.LIVING
        label.contains("رئيسية") && label.contains("نوم") -> RoomKind.MASTER_BEDROOM
        label.contains("نوم") || label.contains("ضيوف") || label.contains("خادمة") || label.contains("سائق") -> RoomKind.BEDROOM
        label.contains("طعام") -> RoomKind.DINING
        label.contains("مطبخ") -> RoomKind.KITCHEN
        label.contains("مكتب") -> RoomKind.OFFICE
        label.contains("ملابس") -> RoomKind.DRESSING
        label.contains("حمام") || label.contains("مغاسل") -> RoomKind.BATHROOM
        else -> RoomKind.NONE
    }

    private fun pointInsidePolygon(point: Vec2, polygon: List<Vec2>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val crosses = (current.z > point.z) != (previous.z > point.z)
            if (crosses) {
                val denominator = previous.z - current.z
                val safe = if (kotlin.math.abs(denominator) < EPSILON) EPSILON else denominator
                val boundaryX = (previous.x - current.x) * (point.z - current.z) / safe + current.x
                if (point.x < boundaryX) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private enum class RoomKind {
        MAJLIS,
        LIVING,
        MASTER_BEDROOM,
        BEDROOM,
        DINING,
        KITCHEN,
        OFFICE,
        DRESSING,
        BATHROOM,
        NONE,
    }

    private data class RoomFrame(
        val center: Vec2,
        val axisX: Float,
        val axisZ: Float,
        val longSpan: Float,
        val shortSpan: Float,
        val area: Float,
    ) {
        fun toWorld(u: Float, v: Float): Vec2 {
            val normalX = -axisZ
            val normalZ = axisX
            return Vec2(
                x = center.x + axisX * u + normalX * v,
                z = center.z + axisZ * u + normalZ * v,
            )
        }

        companion object {
            fun from(room: RoomRegion): RoomFrame? {
                if (room.polygon.size < 3) return null
                val minX = room.polygon.minOf { it.x }
                val maxX = room.polygon.maxOf { it.x }
                val minZ = room.polygon.minOf { it.z }
                val maxZ = room.polygon.maxOf { it.z }
                val spanX = maxX - minX
                val spanZ = maxZ - minZ
                if (spanX < MIN_ROOM_SPAN_METERS || spanZ < MIN_ROOM_SPAN_METERS) return null

                val center = Vec2((minX + maxX) * 0.5f, (minZ + maxZ) * 0.5f)
                val xIsLong = spanX >= spanZ
                val longSpan = if (xIsLong) spanX else spanZ
                val shortSpan = if (xIsLong) spanZ else spanX
                val axisX = if (xIsLong) 1f else 0f
                val axisZ = if (xIsLong) 0f else 1f
                return RoomFrame(
                    center = center,
                    axisX = axisX,
                    axisZ = axisZ,
                    longSpan = longSpan,
                    shortSpan = shortSpan,
                    area = polygonArea(room.polygon),
                )
            }

            private fun polygonArea(polygon: List<Vec2>): Float {
                if (polygon.size < 3) return 0f
                var sum = 0f
                var previous = polygon.last()
                for (current in polygon) {
                    sum += previous.x * current.z - current.x * previous.z
                    previous = current
                }
                return kotlin.math.abs(sum) * 0.5f
            }
        }
    }

    private class PrimitiveBuilder {
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Int>()

        fun addBox(
            centerX: Float,
            centerY: Float,
            centerZ: Float,
            halfAlong: Float,
            halfDepth: Float,
            halfHeight: Float,
            alongX: Float,
            alongZ: Float,
        ) {
            val normalX = -alongZ
            val normalZ = alongX

            fun point(along: Float, depth: Float, y: Float) = P3(
                x = centerX + alongX * along + normalX * depth,
                y = centerY + y,
                z = centerZ + alongZ * along + normalZ * depth,
            )

            val a0 = point(-halfAlong, halfDepth, -halfHeight)
            val b0 = point(halfAlong, halfDepth, -halfHeight)
            val c0 = point(halfAlong, -halfDepth, -halfHeight)
            val d0 = point(-halfAlong, -halfDepth, -halfHeight)
            val a1 = point(-halfAlong, halfDepth, halfHeight)
            val b1 = point(halfAlong, halfDepth, halfHeight)
            val c1 = point(halfAlong, -halfDepth, halfHeight)
            val d1 = point(-halfAlong, -halfDepth, halfHeight)

            addQuad(a0, b0, b1, a1, P3(normalX, 0f, normalZ))
            addQuad(c0, d0, d1, c1, P3(-normalX, 0f, -normalZ))
            addQuad(d0, a0, a1, d1, P3(-alongX, 0f, -alongZ))
            addQuad(b0, c0, c1, b1, P3(alongX, 0f, alongZ))
            addQuad(a1, b1, c1, d1, P3(0f, 1f, 0f))
            addQuad(d0, c0, b0, a0, P3(0f, -1f, 0f))
        }

        private fun addQuad(a: P3, b: P3, c: P3, d: P3, normal: P3) {
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

    private data class P3(val x: Float, val y: Float, val z: Float)

    private const val MIN_ROOM_CONFIDENCE = 0.78f
    private const val MIN_STAGEABLE_AREA_SQ_METERS = 3.0f
    private const val MIN_ROOM_SPAN_METERS = 1.35f
    private const val FLOATS_PER_VERTEX = 6
    private const val EPSILON = 0.000001f
}
