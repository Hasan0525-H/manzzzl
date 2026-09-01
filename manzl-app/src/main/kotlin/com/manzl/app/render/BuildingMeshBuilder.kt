package com.manzl.app.render

import com.manzl.app.design.ReferenceDrivenDesignEngine
import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.DoorHingeSide
import com.manzl.app.model.DoorSwingSide
import com.manzl.app.model.VerticalVoidRoomPolicy

/**
 * Stacks independently measured floor-plan meshes at their declared base elevations.
 *
 * No X/Z registration correction is applied here. Verified structural columns are appended as solid
 * prisms. Trusted vertical-void room faces (service/elevator shafts) are omitted from floor/ceiling
 * generation only after ReconstructionReadinessGate has proved they are independent planar faces, so
 * the omission creates a real hole rather than silently filling the shaft with a rectangular slab.
 */
internal object BuildingMeshBuilder {

    fun build(building: BuildingPlan): MeshData {
        var combined = emptyMeshData()
        for (level in building.levels.sortedBy { it.levelIndex }) {
            val design = ReferenceDrivenDesignEngine.synthesize(level.plan)
            val staticPlan = level.plan.copy(
                doors = level.plan.doors.map { door ->
                    door.copy(
                        hingeSide = DoorHingeSide.UNKNOWN,
                        swingSide = DoorSwingSide.UNKNOWN,
                        swingConfidence = 0f,
                    )
                }
            )
            val surfacePlan = staticPlan.copy(
                rooms = staticPlan.rooms.filterNot(VerticalVoidRoomPolicy::isVerticalVoid),
            )
            val houseMesh = HouseMeshBuilder.build(
                plan = surfacePlan,
                wallHeightOverride = design.wallHeightMeters,
                doorHeightOverride = design.doorHeightMeters,
            )
            val columnMesh = StructuralColumnMeshBuilder.build(
                plan = staticPlan,
                heightOverrideMeters = design.wallHeightMeters,
            )
            val levelMesh = houseMesh
                .append(columnMesh)
                .translatedY(level.baseElevationMeters)
            combined = combined.append(levelMesh)
        }
        return combined
    }
}

internal fun MeshData.translatedY(offsetMeters: Float): MeshData {
    if (offsetMeters == 0f) return this
    return copy(
        wallVertices = wallVertices.translateVerticesY(offsetMeters),
        floorVertices = floorVertices.translateVerticesY(offsetMeters),
        ceilingVertices = ceilingVertices.translateVerticesY(offsetMeters),
        trimVertices = trimVertices.translateVerticesY(offsetMeters),
        glassVertices = glassVertices.translateVerticesY(offsetMeters),
    )
}

internal fun MeshData.append(other: MeshData): MeshData = MeshData(
    wallVertices = wallVertices + other.wallVertices,
    wallIndices = wallIndices.appendIndices(other.wallIndices, wallVertices.vertexCount()),
    floorVertices = floorVertices + other.floorVertices,
    floorIndices = floorIndices.appendIndices(other.floorIndices, floorVertices.vertexCount()),
    ceilingVertices = ceilingVertices + other.ceilingVertices,
    ceilingIndices = ceilingIndices.appendIndices(other.ceilingIndices, ceilingVertices.vertexCount()),
    trimVertices = trimVertices + other.trimVertices,
    trimIndices = trimIndices.appendIndices(other.trimIndices, trimVertices.vertexCount()),
    glassVertices = glassVertices + other.glassVertices,
    glassIndices = glassIndices.appendIndices(other.glassIndices, glassVertices.vertexCount()),
)

private fun FloatArray.translateVerticesY(offsetMeters: Float): FloatArray {
    if (isEmpty()) return this
    val result = copyOf()
    var index = 0
    while (index + FLOATS_PER_VERTEX <= result.size) {
        result[index + 1] += offsetMeters
        index += FLOATS_PER_VERTEX
    }
    return result
}

private fun FloatArray.vertexCount(): Int = size / FLOATS_PER_VERTEX

private fun IntArray.appendIndices(other: IntArray, vertexOffset: Int): IntArray {
    if (other.isEmpty()) return this
    val shifted = IntArray(other.size) { index -> other[index] + vertexOffset }
    return this + shifted
}

private fun emptyMeshData(): MeshData = MeshData(
    wallVertices = floatArrayOf(),
    wallIndices = intArrayOf(),
    floorVertices = floatArrayOf(),
    floorIndices = intArrayOf(),
    ceilingVertices = floatArrayOf(),
    ceilingIndices = intArrayOf(),
    trimVertices = floatArrayOf(),
    trimIndices = intArrayOf(),
    glassVertices = floatArrayOf(),
    glassIndices = intArrayOf(),
)

private const val FLOATS_PER_VERTEX = 6
