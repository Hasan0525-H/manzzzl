package com.manzl.app.analysis

import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import com.manzl.app.model.WindowOpening
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry-only room topology for arbitrary-angle residential plans.
 *
 * Wall centre-lines are split at true intersections, nearby endpoints are snapped conservatively,
 * trusted door/window spans may close a measured opening in the boundary graph, and planar faces are
 * walked from the resulting half-edge graph. The detector never creates a wall or reshapes geometry.
 */
internal object PolygonRoomInferenceEngine {

    fun infer(plan: FloorPlan): List<RoomRegion> {
        val sourceEdges = buildSourceEdges(plan)
        if (sourceEdges.size < 3) return emptyList()

        val splitEdges = splitAtIntersections(sourceEdges)
        val graph = buildGraph(splitEdges)
        if (graph.edges.size < 3) return emptyList()

        val faces = walkFaces(graph)
            .mapNotNull { cycle -> candidateFromCycle(graph, cycle) }
            .distinctBy { it.canonicalKey }
            .sortedBy { it.area }

        if (faces.isEmpty()) return emptyList()

        val accepted = ArrayList<FaceCandidate>()
        for (candidate in faces) {
            val duplicate = accepted.any { existing ->
                distance(existing.centroid, candidate.centroid) <= DUPLICATE_CENTROID_METERS &&
                    areaRatio(existing.area, candidate.area) >= DUPLICATE_MIN_AREA_RATIO
            }
            if (duplicate) continue

            val enclosesSmallerFace = accepted.any { existing ->
                candidate.area > existing.area * OUTER_FACE_AREA_FACTOR &&
                    pointInPolygon(existing.centroid, candidate.polygon)
            }
            if (enclosesSmallerFace) continue

            accepted += candidate
            if (accepted.size >= MAX_POLYGON_ROOMS) break
        }

        return accepted.mapIndexed { index, face ->
            RoomRegion(
                id = "polygon-room-${index + 1}-${face.stableKey}",
                polygon = face.polygon,
                label = null,
                confidence = face.confidence,
            )
        }
    }

    private fun buildSourceEdges(plan: FloorPlan): List<SourceEdge> = buildList {
        plan.walls.forEach { wall ->
            if (distance(wall.start, wall.end) >= MIN_EDGE_METERS) {
                add(SourceEdge(wall.start, wall.end, wall.confidence.coerceIn(0f, 1f), EdgeKind.WALL))
            }
        }
        plan.doors
            .filter { it.confidence >= MIN_DOOR_BOUNDARY_CONFIDENCE }
            .forEach { door -> add(openingEdge(door)) }
        plan.windows
            .filter { it.confidence >= MIN_WINDOW_BOUNDARY_CONFIDENCE }
            .forEach { window -> add(openingEdge(window)) }
    }

    private fun openingEdge(door: DoorOpening): SourceEdge {
        val (a, b) = openingEndpoints(door.center, door.widthMeters, door.rotationDegrees)
        return SourceEdge(a, b, door.confidence * OPENING_CONFIDENCE_FACTOR, EdgeKind.OPENING)
    }

    private fun openingEdge(window: WindowOpening): SourceEdge {
        val (a, b) = openingEndpoints(window.center, window.widthMeters, window.rotationDegrees)
        return SourceEdge(a, b, window.confidence * OPENING_CONFIDENCE_FACTOR, EdgeKind.OPENING)
    }

    private fun openingEndpoints(center: Vec2, widthMeters: Float, rotationDegrees: Float): Pair<Vec2, Vec2> {
        val radians = rotationDegrees * PI.toFloat() / 180f
        val ux = cos(radians)
        val uz = sin(radians)
        val half = widthMeters.coerceAtLeast(0f) * 0.5f
        return Vec2(center.x - ux * half, center.z - uz * half) to
            Vec2(center.x + ux * half, center.z + uz * half)
    }

    private fun splitAtIntersections(edges: List<SourceEdge>): List<SourceEdge> {
        val parameters = Array(edges.size) { mutableListOf(0f, 1f) }
        for (i in 0 until edges.lastIndex) {
            val a = edges[i]
            for (j in i + 1 until edges.size) {
                val b = edges[j]
                val intersection = segmentIntersection(a.a, a.b, b.a, b.b) ?: continue
                if (intersection.tA in INTERSECTION_EPSILON..(1f - INTERSECTION_EPSILON)) {
                    parameters[i] += intersection.tA
                }
                if (intersection.tB in INTERSECTION_EPSILON..(1f - INTERSECTION_EPSILON)) {
                    parameters[j] += intersection.tB
                }
            }
        }

        val result = ArrayList<SourceEdge>()
        edges.forEachIndexed { index, edge ->
            val ts = parameters[index]
                .sorted()
                .fold(ArrayList<Float>()) { acc, value ->
                    if (acc.isEmpty() || abs(acc.last() - value) > PARAMETER_MERGE_EPSILON) acc += value
                    acc
                }
            for (segmentIndex in 0 until ts.lastIndex) {
                val from = interpolate(edge.a, edge.b, ts[segmentIndex])
                val to = interpolate(edge.a, edge.b, ts[segmentIndex + 1])
                if (distance(from, to) >= MIN_SPLIT_EDGE_METERS) {
                    result += edge.copy(a = from, b = to)
                }
            }
        }
        return result
    }

    private fun buildGraph(edges: List<SourceEdge>): Graph {
        val nodes = ArrayList<GraphNode>()
        val graphEdges = ArrayList<GraphEdge>()
        val edgeKeys = HashSet<Long>()

        fun nodeFor(point: Vec2): Int {
            var best = -1
            var bestDistance = ENDPOINT_SNAP_METERS
            for (index in nodes.indices) {
                val d = distance(nodes[index].point, point)
                if (d <= bestDistance) {
                    best = index
                    bestDistance = d
                }
            }
            if (best >= 0) {
                val node = nodes[best]
                val weight = node.sampleCount.toFloat()
                node.point = Vec2(
                    x = (node.point.x * weight + point.x) / (weight + 1f),
                    z = (node.point.z * weight + point.z) / (weight + 1f),
                )
                node.sampleCount += 1
                return best
            }
            nodes += GraphNode(point = point, sampleCount = 1)
            return nodes.lastIndex
        }

        for (edge in edges) {
            val a = nodeFor(edge.a)
            val b = nodeFor(edge.b)
            if (a == b) continue
            val low = min(a, b)
            val high = max(a, b)
            val key = (low.toLong() shl 32) xor high.toLong()
            if (!edgeKeys.add(key)) {
                val existingIndex = graphEdges.indexOfFirst {
                    min(it.a, it.b) == low && max(it.a, it.b) == high
                }
                if (existingIndex >= 0 && edge.confidence > graphEdges[existingIndex].confidence) {
                    graphEdges[existingIndex] = graphEdges[existingIndex].copy(
                        confidence = edge.confidence,
                        kind = if (edge.kind == EdgeKind.WALL) EdgeKind.WALL else graphEdges[existingIndex].kind,
                    )
                }
                continue
            }
            graphEdges += GraphEdge(a, b, edge.confidence, edge.kind)
        }

        val adjacency = Array(nodes.size) { ArrayList<Int>() }
        graphEdges.forEachIndexed { edgeIndex, edge ->
            adjacency[edge.a] += edgeIndex
            adjacency[edge.b] += edgeIndex
        }
        return Graph(nodes, graphEdges, adjacency)
    }

    private fun walkFaces(graph: Graph): List<List<Int>> {
        val visited = HashSet<Long>()
        val faces = ArrayList<List<Int>>()

        graph.edges.forEach { edge ->
            val directions = arrayOf(edge.a to edge.b, edge.b to edge.a)
            for ((startU, startV) in directions) {
                val startKey = directedKey(startU, startV)
                if (startKey in visited) continue

                val cycle = ArrayList<Int>()
                var u = startU
                var v = startV
                var guard = 0
                var closed = false

                while (guard++ < MAX_FACE_WALK_STEPS) {
                    val key = directedKey(u, v)
                    if (!visited.add(key)) {
                        closed = u == startU && v == startV
                        break
                    }
                    cycle += u

                    val next = clockwiseNeighbor(graph, current = v, incoming = u) ?: break
                    u = v
                    v = next
                    if (u == startU && v == startV) {
                        closed = true
                        break
                    }
                }

                if (closed && cycle.size >= 3 && cycle.distinct().size == cycle.size) {
                    faces += cycle
                }
            }
        }
        return faces
    }

    private fun clockwiseNeighbor(graph: Graph, current: Int, incoming: Int): Int? {
        val neighbors = graph.adjacency[current]
            .map { edgeIndex -> graph.edges[edgeIndex].other(current) }
            .distinct()
        if (neighbors.size < 2) return null

        val center = graph.nodes[current].point
        val sorted = neighbors.sortedBy { neighbor ->
            val point = graph.nodes[neighbor].point
            normalizeAngle(atan2(point.z - center.z, point.x - center.x))
        }
        val incomingIndex = sorted.indexOf(incoming)
        if (incomingIndex < 0) return null
        val nextIndex = if (incomingIndex == 0) sorted.lastIndex else incomingIndex - 1
        return sorted[nextIndex]
    }

    private fun candidateFromCycle(graph: Graph, rawCycle: List<Int>): FaceCandidate? {
        // Read confidence/opening evidence from the original half-edge cycle before visual polygon
        // simplification. Collinear simplification can legitimately remove the two graph nodes on
        // either side of a door/window gap; querying only the simplified vertices would then look for
        // a non-existent direct edge and incorrectly reject an otherwise closed room.
        var totalBoundaryLength = 0f
        var openingBoundaryLength = 0f
        var weightedConfidence = 0f
        var minEdgeConfidence = 1f
        for (index in rawCycle.indices) {
            val a = rawCycle[index]
            val b = rawCycle[(index + 1) % rawCycle.size]
            val edge = graph.edgeBetween(a, b) ?: return null
            val edgeLength = distance(graph.nodes[a].point, graph.nodes[b].point)
            if (edgeLength <= 1e-5f) continue
            totalBoundaryLength += edgeLength
            weightedConfidence += edge.confidence * edgeLength
            minEdgeConfidence = min(minEdgeConfidence, edge.confidence)
            if (edge.kind == EdgeKind.OPENING) openingBoundaryLength += edgeLength
        }
        if (totalBoundaryLength <= 1e-5f) return null
        val openingFraction = openingBoundaryLength / totalBoundaryLength
        if (openingFraction > MAX_OPENING_BOUNDARY_FRACTION) return null
        val meanEdgeConfidence = (weightedConfidence / totalBoundaryLength).coerceIn(0f, 1f)

        val simplifiedIds = simplifyCollinear(graph, rawCycle)
        if (simplifiedIds.size < 3 || simplifiedIds.size > MAX_FACE_VERTICES) return null
        val polygon = simplifiedIds.map { graph.nodes[it].point }
        if (!isSimplePolygon(polygon)) return null

        val signedArea = signedArea(polygon)
        val area = abs(signedArea)
        if (area !in MIN_ROOM_AREA_SQ_METERS..MAX_ROOM_AREA_SQ_METERS) return null

        val perimeter = polygon.indices.sumOf { index ->
            distance(polygon[index], polygon[(index + 1) % polygon.size]).toDouble()
        }.toFloat()
        if (perimeter <= 0f) return null
        val compactness = (4f * PI.toFloat() * area / (perimeter * perimeter)).coerceIn(0f, 1f)
        if (compactness < MIN_COMPACTNESS) return null

        val areaPlausibility = when (area) {
            in 3.0f..55f -> 1f
            in 1.5f..80f -> 0.86f
            else -> 0.70f
        }
        val confidence = (
            meanEdgeConfidence * 0.54f +
                minEdgeConfidence * 0.18f +
                areaPlausibility * 0.18f +
                compactness * 0.10f
            ).coerceIn(0f, 0.96f)
        if (confidence < MIN_ROOM_CONFIDENCE) return null

        val centroid = polygonCentroid(polygon, signedArea) ?: return null
        return FaceCandidate(
            polygon = polygon,
            area = area,
            centroid = centroid,
            confidence = confidence,
            canonicalKey = canonicalCycleKey(simplifiedIds),
            stableKey = polygon.joinToString("-") { p ->
                "${kotlin.math.round(p.x * 20f).toInt()}_${kotlin.math.round(p.z * 20f).toInt()}"
            }.take(120),
        )
    }

    private fun simplifyCollinear(graph: Graph, cycle: List<Int>): List<Int> {
        val result = cycle.toMutableList()
        var changed = true
        while (changed && result.size > 3) {
            changed = false
            for (index in result.indices) {
                val previous = graph.nodes[result[(index - 1 + result.size) % result.size]].point
                val current = graph.nodes[result[index]].point
                val next = graph.nodes[result[(index + 1) % result.size]].point
                val area2 = abs(cross(current - previous, next - current))
                val scale = max(distance(previous, current), distance(current, next)).coerceAtLeast(0.01f)
                if (area2 / scale <= COLLINEAR_DISTANCE_METERS) {
                    result.removeAt(index)
                    changed = true
                    break
                }
            }
        }
        return result
    }

    private fun isSimplePolygon(points: List<Vec2>): Boolean {
        if (points.size < 3) return false
        for (i in points.indices) {
            val a0 = points[i]
            val a1 = points[(i + 1) % points.size]
            for (j in i + 1 until points.size) {
                if (j == i || j == (i + 1) % points.size) continue
                if (i == 0 && j == points.lastIndex) continue
                val b0 = points[j]
                val b1 = points[(j + 1) % points.size]
                if (segmentIntersection(a0, a1, b0, b1) != null) return false
            }
        }
        return true
    }

    private fun canonicalCycleKey(ids: List<Int>): String {
        fun rotations(values: List<Int>): Sequence<String> = sequence {
            for (offset in values.indices) {
                yield(values.indices.joinToString(",") { index -> values[(index + offset) % values.size].toString() })
            }
        }
        val forward = rotations(ids)
        val reverse = rotations(ids.reversed())
        return (forward + reverse).minOrNull() ?: ids.joinToString(",")
    }

    private fun polygonCentroid(points: List<Vec2>, signedArea: Float): Vec2? {
        if (abs(signedArea) < 1e-5f) return null
        var cx = 0f
        var cz = 0f
        var crossSum = 0f
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            val c = a.x * b.z - b.x * a.z
            crossSum += c
            cx += (a.x + b.x) * c
            cz += (a.z + b.z) * c
        }
        if (abs(crossSum) < 1e-5f) return null
        return Vec2(cx / (3f * crossSum), cz / (3f * crossSum))
    }

    private fun signedArea(points: List<Vec2>): Float {
        var sum = 0f
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            sum += a.x * b.z - b.x * a.z
        }
        return sum * 0.5f
    }

    private fun pointInPolygon(point: Vec2, polygon: List<Vec2>): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            val crosses = ((pi.z > point.z) != (pj.z > point.z)) &&
                point.x < (pj.x - pi.x) * (point.z - pi.z) / (pj.z - pi.z + 1e-8f) + pi.x
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }

    private fun segmentIntersection(a0: Vec2, a1: Vec2, b0: Vec2, b1: Vec2): Intersection? {
        val r = a1 - a0
        val s = b1 - b0
        val denominator = cross(r, s)
        if (abs(denominator) < PARALLEL_EPSILON) return null
        val delta = b0 - a0
        val t = cross(delta, s) / denominator
        val u = cross(delta, r) / denominator
        if (t < -INTERSECTION_EPSILON || t > 1f + INTERSECTION_EPSILON) return null
        if (u < -INTERSECTION_EPSILON || u > 1f + INTERSECTION_EPSILON) return null
        return Intersection(t.coerceIn(0f, 1f), u.coerceIn(0f, 1f))
    }

    private fun interpolate(a: Vec2, b: Vec2, t: Float): Vec2 =
        Vec2(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t)

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun areaRatio(a: Float, b: Float): Float = min(a, b) / max(a, b).coerceAtLeast(1e-5f)

    private fun normalizeAngle(angle: Float): Float {
        val twoPi = 2f * PI.toFloat()
        var result = angle % twoPi
        if (result < 0f) result += twoPi
        return result
    }

    private fun cross(a: Vec2, b: Vec2): Float = a.x * b.z - a.z * b.x
    private operator fun Vec2.minus(other: Vec2): Vec2 = Vec2(x - other.x, z - other.z)

    private fun directedKey(a: Int, b: Int): Long = (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)

    private data class SourceEdge(
        val a: Vec2,
        val b: Vec2,
        val confidence: Float,
        val kind: EdgeKind,
    )

    private enum class EdgeKind { WALL, OPENING }

    private data class Intersection(val tA: Float, val tB: Float)

    private data class GraphNode(var point: Vec2, var sampleCount: Int)

    private data class GraphEdge(
        val a: Int,
        val b: Int,
        val confidence: Float,
        val kind: EdgeKind,
    ) {
        fun other(node: Int): Int = if (node == a) b else a
    }

    private data class Graph(
        val nodes: List<GraphNode>,
        val edges: List<GraphEdge>,
        val adjacency: Array<ArrayList<Int>>,
    ) {
        fun edgeBetween(a: Int, b: Int): GraphEdge? = adjacency[a]
            .asSequence()
            .map { edges[it] }
            .firstOrNull { edge -> edge.other(a) == b }
    }

    private data class FaceCandidate(
        val polygon: List<Vec2>,
        val area: Float,
        val centroid: Vec2,
        val confidence: Float,
        val canonicalKey: String,
        val stableKey: String,
    )

    private const val MIN_EDGE_METERS = 0.22f
    private const val MIN_SPLIT_EDGE_METERS = 0.08f
    private const val ENDPOINT_SNAP_METERS = 0.16f
    private const val COLLINEAR_DISTANCE_METERS = 0.055f
    private const val INTERSECTION_EPSILON = 0.0015f
    private const val PARAMETER_MERGE_EPSILON = 0.0025f
    private const val PARALLEL_EPSILON = 1e-5f
    private const val MIN_DOOR_BOUNDARY_CONFIDENCE = 0.58f
    private const val MIN_WINDOW_BOUNDARY_CONFIDENCE = 0.66f
    private const val OPENING_CONFIDENCE_FACTOR = 0.90f
    private const val MAX_OPENING_BOUNDARY_FRACTION = 0.36f
    private const val MIN_ROOM_AREA_SQ_METERS = 1.5f
    private const val MAX_ROOM_AREA_SQ_METERS = 120f
    private const val MIN_COMPACTNESS = 0.075f
    private const val MIN_ROOM_CONFIDENCE = 0.61f
    private const val DUPLICATE_CENTROID_METERS = 0.28f
    private const val DUPLICATE_MIN_AREA_RATIO = 0.78f
    private const val OUTER_FACE_AREA_FACTOR = 1.28f
    private const val MAX_FACE_VERTICES = 32
    private const val MAX_FACE_WALK_STEPS = 512
    private const val MAX_POLYGON_ROOMS = 72
}

/**
 * Unified room topology. Polygon faces have priority when they preserve a non-rectilinear boundary;
 * legacy rectangular inference remains as a conservative fallback for weak/sparse graphs.
 */
internal object RoomTopologyEngine {

    fun infer(plan: FloorPlan): List<RoomRegion> {
        val polygonal = PolygonRoomInferenceEngine.infer(plan)
        val rectilinear = RoomInferenceEngine.infer(plan)
        if (polygonal.isEmpty()) return rectilinear
        if (rectilinear.isEmpty()) return polygonal

        val result = ArrayList<RoomRegion>()
        result += polygonal.sortedByDescending { it.confidence }
        for (candidate in rectilinear.sortedByDescending { it.confidence }) {
            val center = centroid(candidate.polygon) ?: continue
            val area = polygonArea(candidate.polygon)
            val duplicate = result.any { existing ->
                val existingCenter = centroid(existing.polygon) ?: return@any false
                distance(center, existingCenter) <= 0.30f &&
                    min(area, polygonArea(existing.polygon)) /
                    max(area, polygonArea(existing.polygon)).coerceAtLeast(1e-4f) >= 0.72f
            }
            if (!duplicate) result += candidate
        }
        return result.take(72)
    }

    private fun centroid(points: List<Vec2>): Vec2? {
        if (points.isEmpty()) return null
        return Vec2(
            points.sumOf { it.x.toDouble() }.toFloat() / points.size,
            points.sumOf { it.z.toDouble() }.toFloat() / points.size,
        )
    }

    private fun polygonArea(points: List<Vec2>): Float {
        if (points.size < 3) return 0f
        var sum = 0f
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            sum += a.x * b.z - b.x * a.z
        }
        return abs(sum) * 0.5f
    }

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }
}
